export interface MermaidInvalidFixture {
  name: string
  source: string
  type: string
}

export interface MermaidValidFixture {
  name: string
  source: string
  type: string
  visible: string[]
}

export const validFixtures: MermaidValidFixture[] = [
  {
    name: 'flow cluster references',
    source: 'flowchart LR\nsubgraph A[Cluster A]\nX\nend\nsubgraph B[Cluster B]\nY\nend\nA --> B',
    type: 'flowchart',
    visible: ['Cluster A', 'Cluster B', 'X', 'Y'],
  },
  {
    name: 'flow numeric and case-sensitive identifiers',
    source:
      'flowchart LR\n401[Unauthorized] --> 403[Forbidden]\nGraph[Graph] --> Style[Style]\nloop --> END',
    type: 'flowchart',
    visible: ['Unauthorized', 'Forbidden', 'Graph', 'Style', 'loop', 'END'],
  },
  {
    name: 'sequence autonumber',
    source: 'sequenceDiagram\r\nautonumber\r\nA->>B: hello\r\n',
    type: 'sequenceDiagram',
    visible: ['A', 'B', 'hello', '1'],
  },
  {
    name: 'state left block note',
    source: 'stateDiagram-v2\n[*] --> A\nnote left of A\nfirst\nsecond\nend note',
    type: 'stateDiagram-v2',
    visible: ['A', 'first', 'second'],
  },
  {
    name: 'state right block note',
    source: 'stateDiagram-v2\n[*] --> A\nnote right of A\nfirst\nend note',
    type: 'stateDiagram-v2',
    visible: ['A', 'first'],
  },
  {
    name: 'state compact transition labels',
    source: 'stateDiagram-v2\n[*] --> Setup: Before Class\nSetup --> Active: Provisioned\nActive --> [*]',
    type: 'stateDiagram-v2',
    visible: ['Before Class', 'Provisioned', 'Setup', 'Active'],
  },
  {
    name: 'flow quoted ER markers',
    source: 'flowchart LR\nA["}o--o{"] --> B',
    type: 'flowchart',
    visible: ['}o--o{'],
  },
  {
    name: 'flow circle cross and teardrop edges',
    source: 'flowchart LR\nA o--o B\nC x--x D\nE --o F\nG --x H',
    type: 'flowchart',
    visible: ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H'],
  },
  {
    name: 'sequence note angle text',
    source: 'sequenceDiagram\nA->>B: hi\nNote over A,B: a < b',
    type: 'sequenceDiagram',
    visible: ['hi', 'a < b'],
  },
  {
    name: 'sequence encoded semicolon',
    source: 'sequenceDiagram\nA->>B: hello#59; world',
    type: 'sequenceDiagram',
    visible: ['hello; world'],
  },
  {
    name: 'ER bare relation and parameterized types',
    source:
      'erDiagram\nA {\nvarchar(255) name\nstring[] tags\ndecimal(10,2) amount\n}\nA ||--o{ B : has many',
    type: 'erDiagram',
    visible: ['varchar(255)', 'string[]', 'decimal(10,2)', 'has many'],
  },
  {
    name: 'flowchart common source forms',
    source:
      'graph LR\r\nsubgraph External\r\n  client[Client App]\r\nend\r\nsubgraph API[API Layer]\r\n  REP-->DB[(PostgreSQL)]\r\nend\r\nclient-->|reads|REP-->DB\r\n',
    type: 'flowchart',
    visible: ['External', 'Client App', 'API Layer', 'PostgreSQL', 'reads'],
  },
  {
    name: 'flowchart classic shapes',
    source:
      'flowchart TB\nA[API Layer]\nB(API Layer)\nC([Start])\nD[(PostgreSQL)]\nE[[Routine]]\nF((Circle))\nG(((Double)))\nH{Decision}\nI{{Prepare}}\nJ>Asymmetric]\nK[/Data/]\nL[\\Data\\]\nM[/Shape\\]\nN[\\Shape/]',
    type: 'flowchart',
    visible: [
      'API Layer',
      'Start',
      'PostgreSQL',
      'Routine',
      'Circle',
      'Double',
      'Decision',
      'Prepare',
      'Asymmetric',
      'Data',
      'Shape',
    ],
  },
  {
    name: 'flowchart implicit groups',
    source:
      'flowchart LR\nsubgraph Outer Services\nsubgraph Inner Systems\nA\nend\nend\nsubgraph External\nB\nend\nsubgraph Worker Fleet\nC\nend',
    type: 'flowchart',
    visible: ['Outer Services', 'Inner Systems', 'External', 'Worker Fleet'],
  },
  {
    name: 'flowchart long arrows and chains',
    source: 'flowchart LR\nA---oB---xC\nD===oE===xF\nG-.-oH-.-xI',
    type: 'flowchart',
    visible: ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I'],
  },
  {
    name: 'flowchart repaired punctuation',
    source:
      'flowchart LR\nA[API (v1)] -->|Read; write| B[Value: 2]\nsubgraph Edge Services (External)\nC[Start & go]\nend',
    type: 'flowchart',
    visible: ['API (v1)', 'Read; write', 'Value: 2', 'Edge Services (External)', 'Start & go'],
  },
  {
    name: 'flowchart explicit literal ampersand',
    source: 'flowchart\nA["R#38;D"]',
    type: 'flowchart',
    visible: ['R&D'],
  },
  {
    name: 'flowchart dotted arrow tokens',
    source: 'flowchart\nA -.-> B\nB -.- C\nC <-.-> D',
    type: 'flowchart',
    visible: ['A', 'B', 'C', 'D'],
  },
  {
    name: 'sequence authoring wrappers',
    source:
      'sequenceDiagram\ntitle "Access; flow"\nparticipant A as "Alice (team)"\nparticipant B as Bob\nalt "Ready?"\nA->>B: "Send; now"\nNote over A,B: "Message note"\nelse "NotReady"\nB-->>A: "Wait"\nend',
    type: 'sequenceDiagram',
    visible: [
      'Access; flow',
      'Alice (team)',
      'Ready?',
      'Send; now',
      'Message note',
      'NotReady',
      'Wait',
    ],
  },
  {
    name: 'sequence literal interior quotes and entities',
    source:
      'sequenceDiagram\nparticipant A\nparticipant B\nA->>B: He said "yes" today\nB-->>A: "Reply #quot;hello#quot;"',
    type: 'sequenceDiagram',
    visible: ['He said "yes" today', 'Reply "hello"'],
  },
  {
    name: 'sequence balanced activation',
    source: 'sequenceDiagram\nparticipant A\nparticipant B\nA->>+B: call\nB-->>-A: done',
    type: 'sequenceDiagram',
    visible: ['call', 'done'],
  },
  {
    name: 'class authoring wrappers',
    source:
      'classDiagram\nclass User {\n"+nickname: string"\n}\nclass Team\nUser --> Team : "linked; here"',
    type: 'classDiagram',
    visible: ['+nickname: string', 'linked; here'],
  },
  {
    name: 'class UML members',
    source:
      'classDiagram\nclass Repo~T, ID~ {\n<<interface>>\n+save(entity) Result\n+find(id): T\n+items List<String>\n-List~T~ cache\n}\nnote for Repo "Data access"',
    type: 'classDiagram',
    visible: ['«interface»', 'save(entity)', 'find(id)', 'List<String>', 'Data access'],
  },
  {
    name: 'class interface suffix and reverse relations',
    source:
      'classDiagram\nclass JpaRepository<<interface>>\nclass ServiceInterface\nclass ServiceImpl\nclass OpenAIClient\nServiceInterface <|.. ServiceImpl\nJpaRepository <|.. ServiceImpl : implements\nServiceImpl <.. OpenAIClient : uses',
    type: 'classDiagram',
    visible: ['«interface»', 'implements', 'uses'],
  },
  {
    name: 'flow encoded angle text',
    source: 'flowchart LR\nA["<valid_utf8_substring>"] --> B',
    type: 'flowchart',
    visible: ['<valid_utf8_substring>'],
  },
  {
    name: 'flow generated subgraph title',
    source: 'flowchart LR\nsubgraph "sidecar/acp"\nX["x"]\nend',
    type: 'flowchart',
    visible: ['sidecar/acp', 'x'],
  },
  {
    name: 'state authoring wrappers',
    source:
      'stateDiagram-v2\nstate "Ready" as Ready\n[*] --> Ready\nnote right of Ready : "State note"\nReady --> [*] : "Finish; now"',
    type: 'stateDiagram-v2',
    visible: ['State note', 'Finish; now'],
  },
  {
    name: 'C4 title',
    source:
      'C4Context\ntitle System Context Diagram for Kratis Control Plane\nPerson(u, "User")\nSystem(s, "System")\nRel(u, s, "Uses")',
    type: 'C4Context',
    visible: ['System Context Diagram for Kratis Control Plane', 'User', 'System', 'Uses'],
  },
  {
    name: 'C4 forward relation',
    source: 'C4Context\nRel(a, b, "Connects")\nPerson(a, "Person")\nSystem(b, "System")',
    type: 'C4Context',
    visible: ['Person', 'System', 'Connects'],
  },
  {
    name: 'pie authoring wrapper',
    source: 'pie\ntitle "Pie; title"\n"Slice" : 1',
    type: 'pie',
    visible: ['Pie; title', 'Slice'],
  },
  {
    name: 'quadrant authoring wrapper',
    source:
      'quadrantChart\ntitle "Quadrant; title"\nx-axis "Low" --> "High"\ny-axis "Low" --> "High"\nquadrant-1 "One"\nquadrant-2 "Two"\nquadrant-3 "Three"\nquadrant-4 "Four"\n"Point": [0.3, 0.7]',
    type: 'quadrantChart',
    visible: ['Quadrant; title', 'Point'],
  },
  {
    name: 'journey authoring wrappers',
    source:
      'journey\ntitle "Visit; shop"\nsection "Arrival: early"\n"Find: entry": 4: "User (A)", "Guide"',
    type: 'journey',
    visible: ['Visit; shop', 'Arrival: early', 'Find: entry', 'User (A)', 'Guide'],
  },
  {
    name: 'flowchart nested labels',
    source:
      'flowchart LR\nsubgraph G["Group (A)"]\nA["Start & go"] -->|"yes; now"| B["Finish"]\nend',
    type: 'flowchart',
    visible: ['Group (A)', 'Start & go', 'yes; now', 'Finish'],
  },
  {
    name: 'sequence blocks',
    source:
      'sequenceDiagram\nparticipant A as Alice (team)\nparticipant B as Bob\nalt ready?\nA->>B: hello; world #1\nelse not ready\nB-->>A: wait\nend',
    type: 'sequenceDiagram',
    visible: ['Alice (team)', 'hello; world #1'],
  },
  {
    name: 'class members and relationship',
    source:
      'classDiagram\nclass User["User (account)"] {\n+name: string\n}\nclass Team\nUser "1" --> "*" Team : belongs; here',
    type: 'classDiagram',
    visible: ['User (account)', '+name: string', 'belongs; here'],
  },
  {
    name: 'state transition',
    source:
      'stateDiagram-v2\nstate "Ready (now)" as Ready\n[*] --> Ready\nReady --> [*] : done; next',
    type: 'stateDiagram-v2',
    visible: ['Ready (now)', 'done; next'],
  },
  {
    name: 'ER attributes',
    source: 'erDiagram\nUSER {\nstring id PK "Key (local)"\n}\nUSER ||--o{ TEAM : "owns; all"',
    type: 'erDiagram',
    visible: ['Key (local)', 'owns; all'],
  },
  {
    name: 'C4 context',
    source:
      'C4Context\nPerson(user, "User (A)", "Uses; system")\nSystem(app, "App & API", "Core")\nRel(user, app, "Calls, reads")',
    type: 'C4Context',
    visible: ['User (A)', 'App & API', 'Calls, reads'],
  },
  {
    name: 'C4 container boundary',
    source:
      'C4Container\nPerson(user, "User")\nSystem_Boundary(app, "App (scope)") {\nContainer(api, "API", "Java", "Handles requests")\n}\nRel(user, api, "Calls")',
    type: 'C4Container',
    visible: ['App (scope)', 'Handles requests'],
  },
  {
    name: 'C4 component boundary',
    source:
      'C4Component\nContainer_Boundary(app, "API (scope)") {\nComponent(service, "Service & cache", "Java", "Work")\n}',
    type: 'C4Component',
    visible: ['API (scope)', 'Service & cache'],
  },
  {
    name: 'pie punctuation',
    source: 'pie\ntitle Sales; total\n"North (Q1)" : 42\n"South & West" : 58',
    type: 'pie',
    visible: ['Sales; total', 'North (Q1)', 'South & West'],
  },
  {
    name: 'quadrant coordinates',
    source:
      'quadrantChart\ntitle Impact; cost\nx-axis "Low (cost)" --> "High cost"\ny-axis "Low value" --> "High value"\nquadrant-1 "Plan (now)"\nquadrant-2 "Try"\nquadrant-3 "Skip"\nquadrant-4 "Later"\n"Item; A": [0.3, 0.7]',
    type: 'quadrantChart',
    visible: ['Impact; cost', 'Low (cost)', 'Plan (now)', 'Item; A'],
  },
  {
    name: 'xy categories',
    source:
      'xychart-beta\ntitle "Revenue (Q1)"\nx-axis "Month" ["Jan; 1", "Feb (2)"]\ny-axis "Sales" 0 --> 100\nbar [20, 40]\nline [30, 50]',
    type: 'xychart-beta',
    visible: ['Revenue (Q1)', 'Jan; 1', 'Feb (2)'],
  },
  {
    name: 'journey sections',
    source:
      'journey\ntitle Visit; shop\nsection Arrival: early\nFind door; enter: 4: User (A), Guide\nsection Leave\nGo home: 3: User (A)',
    type: 'journey',
    visible: ['Visit; shop', 'Arrival: early', 'Find door; enter', 'User (A)'],
  },
]

export const invalidFixtures: MermaidInvalidFixture[] = [
  { name: 'flow reserved source', source: 'flowchart\nend["x"] --> B', type: 'flowchart' },
  { name: 'flow reserved target', source: 'flowchart\nA --> class["x"]', type: 'flowchart' },
  {
    name: 'flow reserved group',
    source: 'flowchart\nsubgraph end["x"]\nA\nend',
    type: 'flowchart',
  },
  { name: 'flow bogus dotted arrow', source: 'flowchart\nA -x-> B', type: 'flowchart' },
  { name: 'flow bogus line arrow', source: 'flowchart\nA -z- B', type: 'flowchart' },
  {
    name: 'sequence reserved declaration',
    source: 'sequenceDiagram\nparticipant loop',
    type: 'sequenceDiagram',
  },
  {
    name: 'sequence reserved source',
    source: 'sequenceDiagram\nloop->>A: x',
    type: 'sequenceDiagram',
  },
  {
    name: 'sequence reserved target',
    source: 'sequenceDiagram\nA->>Loop: x',
    type: 'sequenceDiagram',
  },
  {
    name: 'sequence reserved note',
    source: 'sequenceDiagram\nNote over A,loop: x',
    type: 'sequenceDiagram',
  },
  {
    name: 'sequence reserved activation',
    source: 'sequenceDiagram\nactivate Loop',
    type: 'sequenceDiagram',
  },
  {
    name: 'sequence duplicate participant',
    source: 'sequenceDiagram\nparticipant A\nactor A',
    type: 'sequenceDiagram',
  },
  {
    name: 'sequence inactive deactivation',
    source: 'sequenceDiagram\nparticipant A\ndeactivate A',
    type: 'sequenceDiagram',
  },
  {
    name: 'sequence wrong sender deactivation',
    source: 'sequenceDiagram\nA->>+B: call\nA-->>-B: done',
    type: 'sequenceDiagram',
  },
  {
    name: 'sequence unknown activation',
    source: 'sequenceDiagram\nactivate A\ndeactivate A',
    type: 'sequenceDiagram',
  },
  {
    name: 'sequence malformed alias wrapper',
    source: 'sequenceDiagram\nparticipant A as "Alice',
    type: 'sequenceDiagram',
  },
  {
    name: 'sequence wrapper with trailing syntax',
    source: 'sequenceDiagram\nA->>B: "Hello" trailing',
    type: 'sequenceDiagram',
  },
  {
    name: 'sequence odd interior quote',
    source: 'sequenceDiagram\nA->>B: Say "hello',
    type: 'sequenceDiagram',
  },
  {
    name: 'sequence ambiguous nested quotes',
    source: 'sequenceDiagram\nA->>B: "Say "hello""',
    type: 'sequenceDiagram',
  },
  {
    name: 'sequence second statement in alias',
    source: 'sequenceDiagram\nparticipant A as Alice; B->>A: x',
    type: 'sequenceDiagram',
  },
  { name: 'class reserved identifier', source: 'classDiagram\nclass click', type: 'classDiagram' },
  {
    name: 'class ambiguous relation separator',
    source: 'classDiagram\nA --> B : first; C --> D',
    type: 'classDiagram',
  },
  {
    name: 'state reserved identifier',
    source: 'stateDiagram-v2\n[*] --> state',
    type: 'stateDiagram-v2',
  },
  {
    name: 'state unknown note',
    source: 'stateDiagram-v2\nnote right of A : x',
    type: 'stateDiagram-v2',
  },
  {
    name: 'state forward note',
    source: 'stateDiagram-v2\nnote right of A : x\n[*] --> A',
    type: 'stateDiagram-v2',
  },
  {
    name: 'state inline note and transition',
    source: 'stateDiagram-v2\n[*] --> A\nnote right of A : hi; B --> C',
    type: 'stateDiagram-v2',
  },
  {
    name: 'state inline transition',
    source: 'stateDiagram-v2\nA --> B : first; C --> D',
    type: 'stateDiagram-v2',
  },
  { name: 'ER reserved name', source: 'erDiagram\nend ||--o{ B : "x"', type: 'erDiagram' },
  { name: 'ER reserved attribute', source: 'erDiagram\nA {\nstring PK\n}', type: 'erDiagram' },
  {
    name: 'C4 missing source',
    source: 'C4Context\nPerson(b, "B")\nRel(a, b, "calls")',
    type: 'C4Context',
  },
  {
    name: 'C4 missing target',
    source: 'C4Context\nPerson(a, "A")\nRel(a, b, "calls")',
    type: 'C4Context',
  },
  {
    name: 'C4 boundary as endpoint',
    source: 'C4Context\nSystem_Boundary(g, "Group") {\nPerson(a, "A")\n}\nRel(g, a, "calls")',
    type: 'C4Context',
  },
  {
    name: 'C4 duplicate shape',
    source: 'C4Context\nPerson(a, "First")\nSystem(a, "Second")',
    type: 'C4Context',
  },
  {
    name: 'C4 duplicate boundary and shape',
    source: 'C4Context\nSystem_Boundary(a, "Group") {\nPerson(a, "A")\n}',
    type: 'C4Context',
  },
  {
    name: 'C4 empty boundary',
    source: 'C4Context\nSystem_Boundary(a, "Group") {\n}',
    type: 'C4Context',
  },
  { name: 'C4 raw tag', source: 'C4Context\nPerson(a, "<b>Text</b>")', type: 'C4Context' },
  {
    name: 'C4 raw script',
    source: 'C4Context\nPerson(a, "<script>evil</script>")',
    type: 'C4Context',
  },
  {
    name: 'C4 named HTML entity',
    source: 'C4Context\nPerson(a, "&lt;script&gt;")',
    type: 'C4Context',
  },
  {
    name: 'C4 numeric HTML entity',
    source: 'C4Context\nPerson(a, "&#x3c;script&#x3e;")',
    type: 'C4Context',
  },
  { name: 'pie malformed wrapper', source: 'pie\ntitle "Broken\n"Slice" : 1', type: 'pie' },
  {
    name: 'journey malformed role wrapper',
    source: 'journey\nsection A\nTask: 3: "User',
    type: 'journey',
  },
  {
    name: 'journey quoted comma in role',
    source: 'journey\nsection A\nTask: 3: "User, A"',
    type: 'journey',
  },
  {
    name: 'journey encoded comma in role',
    source: 'journey\nsection A\nTask: 3: User#44;A',
    type: 'journey',
  },
  {
    name: 'journey inline task',
    source: 'journey\nsection X\nTask: 3: User; Another: 2: Actor',
    type: 'journey',
  },
  { name: 'flow named HTML entity', source: 'flowchart\nA["&amp;"]', type: 'flowchart' },
  {
    name: 'flowchart unknown trailing syntax',
    source: 'flowchart LR\nA --> B garbage',
    type: 'flowchart',
  },
  {
    name: 'flowchart unmatched nesting',
    source: 'flowchart\nsubgraph G["Group"]\nA --> B',
    type: 'flowchart',
  },
  {
    name: 'flowchart id conflict',
    source: 'flowchart\nsubgraph A["Group"]\nA --> B\nend',
    type: 'flowchart',
  },
  {
    name: 'sequence invalid branch',
    source: 'sequenceDiagram\nloop again\nelse no\nend',
    type: 'sequenceDiagram',
  },
  {
    name: 'sequence second statement',
    source: 'sequenceDiagram\nA->>B: hello; B->>A: reply',
    type: 'sequenceDiagram',
  },
  { name: 'class unclosed body', source: 'classDiagram\nclass A {\n+name', type: 'classDiagram' },
  {
    name: 'class unsupported directive',
    source: 'classDiagram\nclick A "url"',
    type: 'classDiagram',
  },
  {
    name: 'class unsupported arrow',
    source: 'classDiagram\nclass A\nclass B\nA <|?? B',
    type: 'classDiagram',
  },
  {
    name: 'state composite excluded',
    source: 'stateDiagram-v2\nstate A {',
    type: 'stateDiagram-v2',
  },
  { name: 'ER malformed relation', source: 'erDiagram\nA ||--?? B : "owns"', type: 'erDiagram' },
  { name: 'ER invalid attribute', source: 'erDiagram\nA {\nstring id XX\n}', type: 'erDiagram' },
  { name: 'C4 raw entity', source: 'C4Context\nPerson(a, "A#59;B")', type: 'C4Context' },
  {
    name: 'C4 container unclosed boundary',
    source: 'C4Container\nSystem_Boundary(a, "A") {\nContainer(b, "B")',
    type: 'C4Container',
  },
  {
    name: 'C4 component unknown construct',
    source: 'C4Component\nDeployment_Node(a, "A")',
    type: 'C4Component',
  },
  { name: 'pie nonnumeric', source: 'pie\n"A" : nope', type: 'pie' },
  { name: 'pie leading decimal point', source: 'pie\n"A" : .5', type: 'pie' },
  { name: 'pie leading zero', source: 'pie\n"A" : 00', type: 'pie' },
  {
    name: 'quadrant out of range',
    source:
      'quadrantChart\nx-axis "low" --> "high"\ny-axis "low" --> "high"\nquadrant-1 "one"\nquadrant-2 "two"\nquadrant-3 "three"\nquadrant-4 "four"\n"A": [1.1, 0]',
    type: 'quadrantChart',
  },
  {
    name: 'quadrant decimal one',
    source:
      'quadrantChart\nx-axis "low" --> "high"\ny-axis "low" --> "high"\nquadrant-1 "one"\nquadrant-2 "two"\nquadrant-3 "three"\nquadrant-4 "four"\n"A": [1.0, 0]',
    type: 'quadrantChart',
  },
  {
    name: 'quadrant missing captions',
    source: 'quadrantChart\n"A": [0.1, 0.2]',
    type: 'quadrantChart',
  },
  { name: 'xy mismatch', source: 'xychart-beta\nx-axis ["a", "b"]\nbar [1]', type: 'xychart-beta' },
  { name: 'xy nonnumeric', source: 'xychart-beta\nx-axis ["a"]\nbar [bad]', type: 'xychart-beta' },
  { name: 'journey score', source: 'journey\nsection A\nTask: 6: User', type: 'journey' },
  { name: 'journey missing section', source: 'journey\nTask: 3: User', type: 'journey' },
  { name: 'gantt excluded', source: 'gantt\ntitle Test', type: 'gantt' },
  { name: 'timeline excluded', source: 'timeline\n2026 : test', type: 'timeline' },
]
