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
 * Bloated HTTP sessions in Undertow's {@code InMemorySessionManager} (per deployment) and piles of open WebSocket
 * sessions ({@code io.undertow.websockets.jsr.UndertowSession}).
 */
@Component
public class UndertowHttpSessionBloatInspector implements LeakInspectionRule {

    static final String MANAGER = "io.undertow.server.session.InMemorySessionManager";
    static final String WEBSOCKET_SESSION = "io.undertow.websockets.jsr.UndertowSession";
    private static final int SESSION_SAMPLE = 2_000;

    @Override
    public String id() {
        return "undertow.http-session-bloat";
    }

    @Override
    public String title() {
        return "Undertow: раздутые HTTP/WebSocket-сессии";
    }

    @Override
    public FindingCategory category() {
        return FindingCategory.JAKARTA_WILDFLY;
    }

    @Override
    public boolean isApplicable(InspectionContext context) {
        return !context.reader().classesNamed(MANAGER).isEmpty()
                || !context.reader().classesNamed(WEBSOCKET_SESSION).isEmpty();
    }

    @Override
    public List<InspectionFindingDto> inspect(InspectionContext context) {
        List<InspectionFindingDto> findings = new ArrayList<>();
        httpSessions(context, findings);
        webSocketSessions(context, findings);
        return findings;
    }

    private void httpSessions(InspectionContext context, List<InspectionFindingDto> findings) {
        HeapReader reader = context.reader();
        long fatBytes = context.settings().httpSessionFatBytes().toBytes();
        for (Instance manager : HeapReader.instancesOf(reader.withSubclasses(MANAGER))) {
            if (context.outOfBudget()) {
                return;
            }
            Instance sessions = HeapReader.objectField(manager, "sessions");
            long count = reader.size(sessions);
            if (count <= 0) {
                continue;
            }
            long retained = manager.getRetainedSize();
            long average = retained / count;
            boolean many = count >= context.settings().httpSessionsWarning();
            boolean fat = average >= fatBytes;
            boolean heavy = context.shareOfHeap(retained) >= context.settings().collectionHeapShare();
            if (!many && !fat && !heavy) {
                continue;
            }
            String deployment = reader.string(HeapReader.objectField(manager, "deploymentName"), 200);
            Tally attributes = new Tally();
            long[] timeouts = {Long.MAX_VALUE, Long.MIN_VALUE};
            long[] sampled = {0};
            Instance[] largest = {null};
            reader.forEachMapEntry(sessions, SESSION_SAMPLE, (id, session, hash) -> {
                if (session == null) {
                    return true;
                }
                sampled[0]++;
                if (largest[0] == null || session.getRetainedSize() > largest[0].getRetainedSize()) {
                    largest[0] = session;
                }
                long timeout = HeapReader.longField(session, "maxInactiveInterval", 0);
                timeouts[0] = Math.min(timeouts[0], timeout);
                timeouts[1] = Math.max(timeouts[1], timeout);
                reader.forEachMapEntry(HeapReader.objectField(session, "attributes"), 200, (name, value, h) -> {
                    attributes.add("'" + reader.string(name, 100) + "' (" + HeapReader.className(value) + ")",
                            value == null ? 0 : value.getRetainedSize());
                    return true;
                });
                return !context.outOfBudget();
            });

            FindingBuilder finding = context.finding(this, "http:" + manager.getInstanceId())
                    .severity(heavy ? Severity.CRITICAL : Severity.WARNING)
                    .title(String.format("HTTP-сессии деплоймента %s: %,d сессий, %s (в среднем %s на сессию)",
                            deployment == null ? "?" : deployment, count, Bytes.format(retained),
                            Bytes.format(average)))
                    .wasted(retained)
                    .affected(count)
                    .suspect(MANAGER, "sessions")
                    .pathOf(largest[0])
                    .evidence(String.format("Сессий: %,d; удерживается %s (%.1f%% кучи)", count,
                            Bytes.format(retained), 100 * context.shareOfHeap(retained)))
                    .evidence("Таймаут по умолчанию (defaultSessionTimeout): "
                            + timeout(HeapReader.longField(manager, "defaultSessionTimeout", -1)));
            if (sampled[0] > 0) {
                finding.evidence(String.format("maxInactiveInterval в выборке из %,d сессий: от %s до %s",
                        sampled[0], timeout(timeouts[0]), timeout(timeouts[1])));
            }
            attributes.top(8).forEach(entry -> finding.evidence("Атрибут " + entry.format()
                    + " (по выборке сессий)"));
            if (largest[0] != null) {
                finding.evidence("Самая большая сессия: " + Bytes.format(largest[0].getRetainedSize()));
            }
            findings.add(finding.why(WHY_HTTP).fix(FIX_HTTP).build());
        }
    }

    private void webSocketSessions(InspectionContext context, List<InspectionFindingDto> findings) {
        HeapReader reader = context.reader();
        long count = 0;
        long retained = 0;
        Instance largest = null;
        for (Instance session : HeapReader.instancesOf(reader.withSubclasses(WEBSOCKET_SESSION))) {
            if (context.outOfBudget()) {
                break;
            }
            count++;
            retained += session.getRetainedSize();
            if (largest == null || session.getRetainedSize() > largest.getRetainedSize()) {
                largest = session;
            }
        }
        if (count < 1_000 && context.shareOfHeap(retained) < 0.02) {
            return;
        }
        findings.add(context.finding(this, "websocket")
                .severity(context.shareOfHeap(retained) >= context.settings().collectionHeapShare()
                        ? Severity.CRITICAL : Severity.WARNING)
                .title(String.format("Открытых WebSocket-сессий Undertow: %,d (%s)", count, Bytes.format(retained)))
                .wasted(retained)
                .affected(count)
                .suspect(WEBSOCKET_SESSION, null)
                .pathOf(largest)
                .evidence("Самая большая WebSocket-сессия: "
                        + Bytes.format(largest == null ? 0 : largest.getRetainedSize()))
                .why(WHY_WEBSOCKET)
                .fix(FIX_WEBSOCKET)
                .build());
    }

    private static String timeout(long seconds) {
        if (seconds < 0) {
            return "бессрочно (" + seconds + ")";
        }
        return seconds >= 60 ? (seconds / 60) + " мин" : seconds + " с";
    }

    private static final String WHY_HTTP = """
            Сессии Undertow в WildFly (без distributable) хранятся в памяти процесса до истечения таймаута — \
            по умолчанию 30 минут после ПОСЛЕДНЕГО запроса клиента. Память под сессии = число сессий × размер \
            атрибутов.

            Типичные причины роста:
              • клиенты, которые не возвращают cookie JSESSIONID (REST-клиенты, балансировщик с health-check, \
            боты, мобильные приложения): каждый запрос с request.getSession() или @SessionScoped-бином создаёт \
            НОВУЮ сессию, и она живёт 30 минут впустую;
              • в сессию кладут крупные объекты: корзины с полными DTO, результаты поиска, отчёты, byte[] файлов;
              • бессрочный таймаут (maxInactiveInterval < 0) или слишком длинный session-timeout в web.xml;
              • нет invalidate() при выходе пользователя.""";

    private static final String FIX_HTTP = """
            // 1. Для stateless REST — не создавать сессию вовсе
            HttpSession session = request.getSession(false);      // а не getSession() / getSession(true)
            // JAX-RS/JWT: аутентификация по токену, без @SessionScoped-бинов

            // 2. Храните в сессии идентификаторы, а не данные
            session.setAttribute("cartId", cart.getId());           // а не весь Cart с позициями и картинками

            // 3. Таймаут и лимит — web.xml и jboss-web.xml
            // <session-config><session-timeout>15</session-timeout></session-config>
            // <jboss-web><max-active-sessions>20000</max-active-sessions></jboss-web>

            // 4. Завершайте сессию при выходе
            @POST
            @Path("/logout")
            public Response logout(@Context HttpServletRequest request) {
                HttpSession current = request.getSession(false);
                if (current != null) {
                    current.invalidate();
                }
                return Response.noContent().build();
            }""";

    private static final String WHY_WEBSOCKET = """
            Каждая открытая WebSocket-сессия держит буферы, userProperties и объект endpoint. Если клиенты \
            отключаются без корректного закрытия (обрыв сети, мобильные клиенты), а приложение хранит сессии в \
            собственном static Set/Map и не удаляет их в @OnClose/@OnError, сессии копятся бесконечно.""";

    private static final String FIX_WEBSOCKET = """
            @ServerEndpoint("/ws/notifications")
            public class NotificationEndpoint {
                private static final Set<Session> SESSIONS = ConcurrentHashMap.newKeySet();

                @OnOpen
                public void open(Session session) {
                    session.setMaxIdleTimeout(Duration.ofMinutes(5).toMillis());  // закрыть «мёртвые» соединения
                    SESSIONS.add(session);
                }

                @OnClose
                public void close(Session session) {
                    SESSIONS.remove(session);
                }

                @OnError
                public void error(Session session, Throwable error) {
                    SESSIONS.remove(session);                                      // обрыв тоже убирает сессию
                }
            }""";
}
