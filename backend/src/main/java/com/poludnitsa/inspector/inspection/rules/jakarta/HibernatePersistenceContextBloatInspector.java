package com.poludnitsa.inspector.inspection.rules.jakarta;

import com.poludnitsa.inspector.api.dto.FindingCategory;
import com.poludnitsa.inspector.api.dto.InspectionFindingDto;
import com.poludnitsa.inspector.api.dto.Severity;
import com.poludnitsa.inspector.common.Bytes;
import com.poludnitsa.inspector.heap.HeapReader;
import com.poludnitsa.inspector.inspection.FindingBuilder;
import com.poludnitsa.inspector.inspection.InspectionContext;
import com.poludnitsa.inspector.inspection.LeakInspectionRule;
import com.poludnitsa.inspector.inspection.Tally;
import java.util.ArrayList;
import java.util.List;
import org.netbeans.lib.profiler.heap.Instance;
import org.springframework.stereotype.Component;

/**
 * Hibernate sessions whose first-level cache ({@code StatefulPersistenceContext}) holds thousands of managed
 * entities: {@code entityEntryContext.count}, falling back to the size of {@code entitiesByKey}.
 */
@Component
public class HibernatePersistenceContextBloatInspector implements LeakInspectionRule {

    static final String PERSISTENCE_CONTEXT = "org.hibernate.engine.internal.StatefulPersistenceContext";

    @Override
    public String id() {
        return "hibernate.persistence-context-bloat";
    }

    @Override
    public String title() {
        return "Hibernate: раздутый кэш 1-го уровня";
    }

    @Override
    public FindingCategory category() {
        return FindingCategory.JAKARTA_WILDFLY;
    }

    @Override
    public boolean isApplicable(InspectionContext context) {
        return !context.reader().classesNamed(PERSISTENCE_CONTEXT).isEmpty();
    }

    @Override
    public List<InspectionFindingDto> inspect(InspectionContext context) {
        HeapReader reader = context.reader();
        int warning = context.settings().hibernateEntitiesWarning();
        int critical = context.settings().hibernateEntitiesCritical();
        List<InspectionFindingDto> findings = new ArrayList<>();
        for (Instance persistenceContext : HeapReader.instancesOf(reader.withSubclasses(PERSISTENCE_CONTEXT))) {
            if (context.outOfBudget()) {
                break;
            }
            Instance entitiesByKey = HeapReader.objectField(persistenceContext, "entitiesByKey");
            long entities = HeapReader.longField(HeapReader.objectField(persistenceContext, "entityEntryContext"),
                    "count", -1);
            if (entities < 0) {
                entities = reader.size(entitiesByKey);
            }
            if (entities < warning) {
                continue;
            }
            long retained = persistenceContext.getRetainedSize();
            Tally types = new Tally();
            reader.forEachMapEntry(entitiesByKey, 20_000, (key, value, hash) -> {
                Instance entity = HeapReader.className(value).contains("EntityHolder")
                        ? HeapReader.objectField(value, "entity") : value;
                types.add(HeapReader.className(entity), entity == null ? 0 : entity.getSize());
                return true;
            });
            long collections = reader.size(HeapReader.objectField(persistenceContext, "collectionEntries"));
            Instance session = HeapReader.objectField(persistenceContext, "session");
            HeapReader.Owner owner = session == null ? null : reader.owner(session);

            FindingBuilder finding = context.finding(this, String.valueOf(persistenceContext.getInstanceId()))
                    .severity(entities >= critical || context.shareOfHeap(retained) >= 0.05
                            ? Severity.CRITICAL : Severity.WARNING)
                    .title(String.format("Кэш 1-го уровня Hibernate: %,d managed-сущностей в одной сессии (%s)",
                            entities, Bytes.format(retained)))
                    .wasted(retained)
                    .affected(entities)
                    .suspect(owner != null ? owner.className() : "org.hibernate.internal.SessionImpl",
                            owner != null ? owner.field() : null)
                    .pathOf(session != null ? session : persistenceContext)
                    .evidence(String.format("Managed-сущностей: %,d; коллекций в контексте: %,d", entities,
                            Math.max(collections, 0)))
                    .evidence("Контекст персистентности удерживает " + Bytes.format(retained)
                            + " (сущности, снапшоты для dirty checking, прокси и коллекции)")
                    .evidence("Сессия: " + HeapReader.className(session));
            types.top(5).forEach(entry -> finding.evidence("Сущности: " + entry.format()));
            if (owner != null) {
                finding.evidence("Сессию держит: " + owner.describe());
            }
            findings.add(finding.why(WHY).fix(FIX).build());
        }
        return findings;
    }

    private static final String WHY = """
            Каждая сущность, загруженная, сохранённая или обновлённая через EntityManager, регистрируется в \
            контексте персистентности (кэш 1-го уровня). Вместе с ней Hibernate хранит EntityEntry и снимок \
            исходного состояния для dirty checking. Всё это живёт до конца транзакции или до закрытия \
            EntityManager, а не до конца цикла в вашем коде.

            Поэтому:
              • пакетная обработка (импорт, выгрузка, пересчёт) в одной транзакции держит в памяти ВСЕ \
            обработанные сущности: память растёт линейно, а flush при коммите проверяет каждую из них (O(N) на \
            каждый flush);
              • расширенный контекст (@PersistenceContext(type = EXTENDED) в @Stateful EJB или долгая \
            «беседа») накапливает сущности между запросами;
              • выборка getResultList() на сотни тысяч строк материализует их все разом.

            Признак проблемы — тысячи managed-сущностей в одной сессии (см. факты) и сессия, которую держит \
            поток пакетной задачи или EJB.""";

    private static final String FIX = """
            // persistence.xml: <property name="hibernate.jdbc.batch_size" value="50"/>
            //                  <property name="hibernate.order_inserts" value="true"/>

            @Transactional
            public void importOrders(List<OrderRow> rows) {
                int i = 0;
                for (OrderRow row : rows) {
                    em.persist(toEntity(row));
                    if (++i % 50 == 0) {      // кратно hibernate.jdbc.batch_size
                        em.flush();           // отправить SQL-пакет
                        em.clear();           // отпустить managed-сущности из кэша 1-го уровня
                    }
                }
            }

            // Большие выборки только для чтения — потоком, без dirty checking
            try (Stream<Order> orders = em.createQuery("select o from Order o", Order.class)
                    .setHint("org.hibernate.readOnly", true)
                    .setHint("org.hibernate.fetchSize", 500)
                    .getResultStream()) {
                orders.forEach(exporter::write);
            }

            // Массовая обработка без кэша 1-го уровня — StatelessSession
            try (StatelessSession session = sessionFactory.openStatelessSession()) {
                Transaction tx = session.beginTransaction();
                rows.forEach(row -> session.insert(toEntity(row)));
                tx.commit();
            }

            // Вместо одной огромной транзакции — порции по N записей (@Transactional(REQUIRES_NEW) на порцию,
            // JBeret chunk-step с item-count).""";
}
