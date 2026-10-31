/** Ready-made OQL queries (VisualVM / NetBeans dialect), grouped like the inspections. */
export interface OqlTemplate {
  title: string;
  hint: string;
  query: string;
}

export interface OqlTemplateGroup {
  id: string;
  label: string;
  color: 'violet' | 'amber' | 'cyan' | 'emerald';
  templates: OqlTemplate[];
}

export const OQL_TEMPLATES: OqlTemplateGroup[] = [
  {
    id: 'wildfly',
    label: 'WildFly / Jakarta EE',
    color: 'violet',
    templates: [
      {
        title: 'Модули и деплойменты',
        hint: 'Каждый ModuleClassLoader с именем модуля и его retained size',
        query: "select { loader: l, module: l.module.name.toString(), retained: rsizeof(l) }\n"
          + 'from org.jboss.modules.ModuleClassLoader l',
      },
      {
        title: 'ThreadLocal в потоках пула',
        hint: 'Сколько ThreadLocal-значений держат потоки default task-*',
        query: "select { thread: t.name.toString(), threadLocals: t.threadLocals == null ? 0 : t.threadLocals.size }\n"
          + 'from java.lang.Thread t\n'
          + "where t.name.toString().startsWith('default task-')",
      },
      {
        title: 'HTTP-сессии Undertow',
        hint: 'Сессии в памяти с числом атрибутов',
        query: "select { session: s, id: s.sessionId.toString(), attributes: s.attributes.size }\n"
          + 'from io.undertow.server.session.InMemorySessionManager$SessionImpl s',
      },
    ],
  },
  {
    id: 'hibernate',
    label: 'Hibernate',
    color: 'amber',
    templates: [
      {
        title: 'Сессии и managed-сущности',
        hint: 'Размер persistence context каждой открытой Session',
        query: "select { session: s, entities: s.persistenceContext.entitiesByKey.size }\n"
          + 'from org.hibernate.internal.SessionImpl s\n'
          + 'where s.persistenceContext != null',
      },
      {
        title: 'Самые тяжёлые сессии',
        hint: 'Top-10 SessionImpl по retained size',
        query: "select top(heap.objects('org.hibernate.internal.SessionImpl'), 'rsizeof(rhs) - rsizeof(lhs)', 10)",
      },
    ],
  },
  {
    id: 'hazelcast',
    label: 'Hazelcast Client',
    color: 'amber',
    templates: [
      {
        title: 'Крупные сериализованные значения',
        hint: 'HeapData больше 10 КБ (Near Cache, локальные копии)',
        query: "select { data: d, bytes: d.payload.length }\n"
          + 'from com.hazelcast.internal.serialization.impl.HeapData d\n'
          + 'where d.payload != null && d.payload.length > 10240',
      },
      {
        title: 'Прокси распределённых структур',
        hint: 'Каждый IMap / ITopic / IQueue, открытый клиентом',
        query: "select { proxy: p, name: p.name.toString() }\n"
          + 'from instanceof com.hazelcast.client.impl.spi.ClientProxy p',
      },
    ],
  },
  {
    id: 'jvm',
    label: 'JVM',
    color: 'cyan',
    templates: [
      {
        title: 'Длинные строки',
        hint: 'Строки длиннее 1000 символов с началом текста',
        query: "select { text: s.toString().substring(0, 80), length: s.value.length }\n"
          + 'from java.lang.String s\n'
          + 'where s.value.length > 1000',
      },
      {
        title: 'Большие byte[]',
        hint: 'Массивы больше 1 МБ и кто их держит',
        query: "select { array: a, length: a.length, referrers: referrers(a) }\n"
          + 'from byte[] a\n'
          + 'where a.length > 1048576',
      },
      {
        title: 'Огромные HashMap',
        hint: 'Карты больше 10 000 записей с ёмкостью таблицы',
        query: "select { map: m, size: m.size, capacity: m.table == null ? 0 : m.table.length, retained: rsizeof(m) }\n"
          + 'from java.util.HashMap m\n'
          + 'where m.size > 10000',
      },
      {
        title: 'Потоки',
        hint: 'Имена всех потоков',
        query: 'select t.name.toString() from java.lang.Thread t',
      },
    ],
  },
  {
    id: 'app',
    label: 'Мой код',
    color: 'emerald',
    templates: [
      {
        title: 'Экземпляры своего класса',
        hint: 'Замените com.acme.OrderDto на свой класс (instanceof — с наследниками)',
        query: "select { object: o, retained: rsizeof(o) }\n"
          + 'from instanceof com.acme.OrderDto o',
      },
      {
        title: 'Поиск по значению поля',
        hint: 'Объекты, у которых строковое поле равно значению',
        query: 'select o from com.acme.OrderDto o\n'
          + "where o.status != null && o.status.toString() == 'PENDING'",
      },
    ],
  },
];
