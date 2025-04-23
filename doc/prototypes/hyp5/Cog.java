package dumb.hyp5;


import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Optional.empty;

/**
 * Cog - Cognitive Logic Engine (Synthesized Hyperon-Inspired Iteration)
 * <p>
 * A unified cognitive architecture implementing a Hyperon/MeTTa-like symbolic system.
 * It integrates parsing, pattern matching, rule-based rewriting, probabilistic reasoning,
 * attention mechanisms, agent interaction, and **JVM reflection** within a single, consolidated Java class structure.
 * <p>
 * ## Core Design:
 * - **Memory:** Central knowledge store holding immutable {@link Atom} objects (Symbols, Vars, Expressions, Is). Managed by {@link Mem}.
 * - **Unified Representation:** All concepts, relations, rules, procedures, types, states, goals are Atoms.
 * - **MeTTa Syntax:** Uses a LISP-like syntax for representing Atoms textually. Includes a {@link MettaParser} for conversion.
 * - **MeTTa Execution:** An {@link Interp} evaluates expressions by matching against equality (`=`) rules via {@link Unify}.
 * - **Homoiconicity:** Code (MeTTa rules/expressions) *is* data (Atoms), enabling reflection and self-modification.
 * - **Is Atoms:** {@link Is} bridges to Java code/data for I/O, math, environment interaction, **JVM integration (Reflection/MethodHandles)** etc.
 * - **Metadata:** Immutable {@link Value} records (holding {@link Truth}, {@link Pri}, {@link Time}) associated with Atoms via {@link AtomicReference} for atomic updates.
 * - **Probabilistic & Priority Driven:** Truth values handle uncertainty; Pri values (STI/LTI) guide attention and forgetting.
 * - **Agent Framework:** Includes an {@link Agent} model demonstrating perception, reasoning (via evaluation), action, and learning. Key agent logic (control loop, action selection) is designed to be driven by MeTTa evaluation.
 * - **Modern Java:** Leverages Records, Sealed Interfaces, Streams, Concurrent Collections, AtomicReference, MethodHandles for robustness and conciseness.
 * <p>
 * ## Strategic MeTTa Integration Pathways:
 * This version focuses on implementing the JVM integration pathway using Reflection and MethodHandles,
 * while identifying other key areas where Java code can be strategically replaced or driven by MeTTa script.
 * <p>
 * 1.  **Interpreter Strategy (`Interp.evalRecursive`):** *Candidate:* Defining evaluation steps via MeTTa rules. (See comments in {@link Interp#evalRecursive}).
 * 2.  **JVM Integration (`Core.initIsFn` - Java* functions):** ***Implemented:*** The `Java*` Is functions now use Java Reflection/MethodHandles, allowing MeTTa scripts to directly interact with and control the JVM environment. (See implementation in {@link Core#initIsFn} and {@link Jvm}).
 * 3.  **Agent Control Flow (`Agent.run`):** *Candidate:* Defining the main agent loop structure via MeTTa rules. (See comments in {@link Agent#run}).
 * 4.  **Configuration Management (Non-Budget):** *Candidate:* Loading configuration constants from MeTTa atoms. (See comments near configuration constants).
 *
 * @version 6.0 - JVM Reflection Integration Implemented
 */
public final class Cog {

    // --- Configuration Constants (Potential MeTTa Candidates) ---
    // These constants define fixed operational parameters. While budget/Pri parameters
    // are explicitly excluded from MeTTa storage due to performance/dependency concerns,
    // others *could* potentially be loaded from MeTTa atoms like `(: truth-default-sensitivity 1.0)`
    // or `(: interpreter-default-max-depth 15)` during initialization or runtime.
    // This would allow dynamic configuration and reflection via MeTTa itself, increasing
    // self-control, at the cost of a small lookup overhead compared to static finals.
    private static final double TRUTH_DEFAULT_SENSITIVITY = 1.0;
    private static final double TRUTH_MIN_CONFIDENCE_MATCH = 0.01; // Candidate for MeTTa config
    private static final double TRUTH_REVISION_CONFIDENCE_THRESHOLD = 0.1;
    private static final double PRI_INITIAL_STI = 0.2; // Excluded from MeTTa config per guidelines
    private static final double PRI_INITIAL_LTI_FACTOR = 0.1; // Excluded from MeTTa config per guidelines
    private static final double PRI_STI_DECAY_RATE = 0.08; // Excluded from MeTTa config per guidelines
    private static final double PRI_LTI_DECAY_RATE = 0.008; // Excluded from MeTTa config per guidelines
    private static final double PRI_STI_TO_LTI_RATE = 0.02; // Excluded from MeTTa config per guidelines
    private static final double PRI_BOOST_ON_ACCESS = 0.08; // Excluded from MeTTa config per guidelines
    private static final double PRI_BOOST_ON_REVISION_MAX = 0.5; // Excluded from MeTTa config per guidelines
    private static final double PRI_BOOST_ON_GOAL_FOCUS = 0.95; // Excluded from MeTTa config per guidelines
    private static final double PRI_BOOST_ON_PERCEPTION = 0.75; // Excluded from MeTTa config per guidelines
    private static final double PRI_MIN_FORGET_THRESHOLD = 0.015; // Candidate for MeTTa config (if not deemed budget-related)
    private static final long FORGETTING_CHECK_INTERVAL_MS = 12000; // Candidate for MeTTa config
    private static final int FORGETTING_MAX_MEM_SIZE_TRIGGER = 18000; // Candidate for MeTTa config
    private static final int FORGETTING_TARGET_MEM_SIZE_FACTOR = 80; // Candidate for MeTTa config
    private static final int INTERPRETER_DEFAULT_MAX_DEPTH = 15; // Candidate for MeTTa config
    private static final int INTERPRETER_MAX_RESULTS = 50; // Candidate for MeTTa config
    private static final double AGENT_DEFAULT_PERCEPTION_COUNT = 8.0; // Candidate for MeTTa config (Agent behaviour related)
    private static final double AGENT_LEARNED_RULE_COUNT = 1.5; // Candidate for MeTTa config (Agent behaviour related)
    private static final double AGENT_UTILITY_THRESHOLD = 0.1; // Candidate for MeTTa config (Agent behaviour related)
    private static final double AGENT_RANDOM_ACTION_PROBABILITY = 0.05; // Candidate for MeTTa config (Agent behaviour related)

    private static final String VAR_PREFIX = "$";
    private static final Set<String> PROTECTED_SYMBOLS = Set.of(
        // Core syntax and types
        "=", ":", "->", "Type", "True", "False", "Nil", "Number", "Bool", "String", "Atom", "List",
        // Agent-related concepts (often protected)
        "Self", "Goal", "State", "Action", "Utility", "Implies", "Seq", "Act",
        // Core Is operations (implementations are Java, symbols are entry points)
        "match", "eval", "add-atom", "remove-atom", "get-value",
        //control
        "If",
        //arithmetic
        "+", "-", "*", "/", "RandomFloat",
        //comparison
        "==", ">", "<", "IsNil",
        //list
        "Concat", "Let", "IsEmpty",
        // Reference to the current space
        "&self",
        // Agent operations for interacting with game
        "AgentStep", "AgentPerceive", "AgentAvailableActions", "AgentExecute", "AgentLearn", "GetEnv", "CheckGoal", "UpdateUtility",
        //JVM Integration
        "JavaNew", "JavaCall", "JavaStaticCall", "JavaField", "JavaProxy"
    );

    // --- Core Symbols (initialized in Core) ---
    public static Atom SYMBOL_EQ, SYMBOL_COLON, SYMBOL_ARROW, SYMBOL_TYPE, SYMBOL_TRUE, SYMBOL_FALSE, SYMBOL_SELF, SYMBOL_NIL;

    public final Mem mem;
    public final Unify unify;
    public final Agent agent;
    private final Interp interp;
    private final MettaParser parser;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong logicalTime = new AtomicLong(0);
    private final Jvm jvm;
    private @Nullable Game environment; // Hold the current environment for agent Is functions

    public Cog() {
        this.mem = new Mem(this::getLogicalTime);
        this.unify = new Unify(this.mem);
        this.interp = new Interp(this);
        this.agent = new Agent(this); // Agent primarily uses MeTTa eval, see Agent.run()
        this.parser = new MettaParser(this);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "Cog-Maintenance");
            t.setDaemon(true); // Allow JVM exit even if this thread is running
            return t;
        });
        // Schedule periodic memory maintenance (decay, forgetting)
        scheduler.scheduleAtFixedRate(this::performMaintenance, FORGETTING_CHECK_INTERVAL_MS, FORGETTING_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);

        new Core(this); // Initializes symbols, Is functions, core rules, injects JvmBridge
        this.jvm = new Jvm(this); // Initialize the JVM bridge


        System.out.println("Cog (v6.0 JVM Reflection) Initialized. Init Size: " + mem.size());
    }

    // --- Main Demonstration (Illustrates current capabilities including JVM reflection) ---
    public static void main(String[] args) {
        var cog = new Cog();
        try {
            System.out.println("\n--- Cog Synthesized Test Suite (v6.0) ---");

            // --- [1] Parsing & Atom Basics (Foundation) ---
            printSectionHeader(1, "Parsing & Atom Basics");
            var red = cog.parse("Red");
            cog.add(red);
            cog.mem.updateTruth(red, new Truth(0.9, 5.0));
            System.out.println("Parsed Red: " + red + " | Value: " + cog.mem.value(red));

            // --- [2] Unification (Core mechanism, Java implementation) ---
            printSectionHeader(2, "Unification");
            var fact1 = cog.parse("(Knows Self Something)");
            var pattern1 = cog.parse("(Knows Self $w)");
            cog.add(fact1);
            testUnification(cog, "Simple Match", pattern1, fact1);

            // --- [3] MeTTa Evaluation - Core Rules (MeTTa rules + Java Is functions) ---
            printSectionHeader(3, "MeTTa Evaluation - Core Rules");
            evaluateAndPrint(cog, "Peano Add 2+1", cog.parse("(add (S (S Z)) (S Z))")); // Expect (S (S (S Z)))
            evaluateAndPrint(cog, "Arithmetic (2+3)*4", cog.parse("(* (+ 2.0 3.0) 4.0)")); // Expect is:Double:20.0
            evaluateAndPrint(cog, "If (5 > 3)", cog.parse("(If (> 5.0 3.0) FiveIsGreater ThreeIsGreater)")); // Expect FiveIsGreater

            // --- [4] Pattern Matching Query (`match` Is function) ---
            printSectionHeader(4, "Pattern Matching Query");
            cog.load("(Knows Self Something)\n(Knows Dean Something)");
            evaluateAndPrint(cog, "Match Query", cog.parse("(match &self (Knows $p Something) $p)")); // Expect is:List<Atom>:[Self, Dean]

            // --- [5] Metaprogramming (Adding rules via `add-atom` Is function) ---
            printSectionHeader(5, "Metaprogramming");
            var ruleToAddAtom = cog.parse("(= (NewPred ConceptX) ResultX)");
            evaluateAndPrint(cog, "Add Rule Meta", cog.E(cog.S("add-atom"), ruleToAddAtom)); // Adds the rule
            evaluateAndPrint(cog, "Test New Rule", cog.parse("(NewPred ConceptX)")); // Expect [ResultX]

            // --- [6] Agent Simulation (Driven by MeTTa `AgentStep` evaluation) ---
            printSectionHeader(6, "Agent Simulation (MeTTa Driven)");
            cog.load("""
                    ; Basic Agent rules using Is primitives
                    (= (AgentStep $agent)
                       (AgentLearn $agent (AgentExecute $agent (AgentSelectAction $agent (AgentPerceive $agent)))))
                    
                    ; Simple learning: Update utility based on reward from Act result
                    (= (AgentLearn $agent (Act $percepts $reward))
                       (UpdateUtility $agent $reward)) ; Simplified: Doesn't use state/action yet
                    
                    ; Placeholder utility update (adds reward to a generic Utility atom)
                    (= (UpdateUtility $agent $reward)
                       (Let (= $utilAtom (= (Utility $agent) $val))
                         (Let $currentUtil (match &self $utilAtom $val)
                           (Let $newUtil (+ (FirstNumOrZero $currentUtil) $reward) ; Needs FirstNumOrZero helper
                             (add-atom (= (Utility $agent) $newUtil))))))
                    
                    ; Helper to get first number from result list (often from match)
                    (= (FirstNumOrZero ($num . $rest)) $num) :- (: $num Number)
                    (= (FirstNumOrZero ($other . $rest)) (FirstNumOrZero $rest))
                    (= (FirstNumOrZero $num) $num) :- (: $num Number)
                    (= (FirstNumOrZero Nil) 0.0)
                    (= (FirstNumOrZero $any) 0.0) ; Default
                    
                    ; Action selection: Random for now
                    (= (AgentSelectAction $agent ($state $actions)) ; Input: (State ActionsList)
                       (RandomChoice $actions)) ; Needs RandomChoice helper Is function
                    
                    ; Placeholder for random choice from Is<List>
                    (= (RandomChoice Nil) Nil)
                    (= (RandomChoice ($a . $b)) $a) ; Simplistic: always pick first
                    
                    ; Goal Check Rule (Example)
                    (= (CheckGoal $agent $goalPattern)
                       (If (IsEmpty (match &self $goalPattern $goalPattern)) False True))
                    """);
            // Add required helper Is functions (simplistic versions)
            cog.IS("RandomChoice", a -> a.stream().findFirst()
                    .flatMap(arg -> cog.evalBest(arg)) // Evaluate the argument (should be Is<List<Atom>>)
                    .flatMap(Core::listValue) // Extract List<Atom>
                    .filter(list -> !list.isEmpty())
                    .map(list -> list.get(RandomGenerator.getDefault().nextInt(list.size())))); // Choose random element
            //.or(() -> Optional.of(SYMBOL_NIL)); // Return Nil if list is empty or arg is wrong type

            var env = new SimpleGame(cog);
            var agentGoal = cog.parse("(State Self AtGoal)");
            cog.runAgent(env, agentGoal, 10);

            System.out.println("\nQuerying agent utility after MeTTa-driven run:");
            var utilQuery = cog.parse("(= (Utility Self) $val)");
            var utilResults = cog.query(utilQuery);
            printQueryResults(utilResults);

            // --- [7] JVM Integration (Implemented Examples) ---
            printSectionHeader(7, "JVM Integration (Reflection/MethodHandles)");
            // Example: Create a Java String using MeTTa
            evaluateAndPrint(cog, "Java New String", cog.parse("(JavaNew \"java.lang.String\" \"Hello from MeTTa\")"));
            // Example: Call a static Java method (Math.max)
            evaluateAndPrint(cog, "Java Static Math.max", cog.parse("(JavaStaticCall \"java.lang.Math\" \"max\" 5.0 10.0)")); // Use doubles
            // Example: Create an object (ArrayList), call its method (add), call another (size)
            evaluateAndPrint(cog, "Java Instance Call",
                    cog.parse("(Let (= $list (JavaNew \"java.util.ArrayList\")) " + // Create list
                            "     (JavaCall $list \"add\" \"item1\") " + // Add item (returns bool Is<Boolean>)
                            "     (JavaCall $list \"size\"))"));         // Get size (returns Is<Integer> -> Is<Double>)
            // Example: Access a static field (Math.PI)
            evaluateAndPrint(cog, "Java Static Field Get", cog.parse("(JavaStaticCall \"java.lang.Math\" \"PI\")")); // Access static field via method call syntax for simplicity or add dedicated static field access
            evaluateAndPrint(cog, "Java Instance Field Get/Set (Conceptual - requires test class)",
                    cog.parse("; Assuming TestClass with public String myField exists\n" +
                            " (Let (= $obj (JavaNew \"dumb.hyp5.Cog$TestClass\"))\n" +
                            "   (JavaField $obj \"myField\" \"NewValue\") ; Set field\n" +
                            "   (JavaField $obj \"myField\")) ; Get field -> Is<String:NewValue>"));
            System.out.println("Note: Field access examples might need specific test classes or adjusted syntax.");
            // Example: Create a Proxy implementing Runnable
            cog.load("""
                    ; Define handler logic for Runnable proxy
                    (= (MyRunnableHandler $proxy $methodName $args)
                       (Concat "Runnable proxy executed method: " $methodName)) ; Simple handler returns string
                    """);
            evaluateAndPrint(cog, "Java Proxy Runnable",
                    cog.parse("(Let (= $handler (MyRunnableHandler $proxy $method $args)) ; Define handler template\n" +
                            "     (= $proxy (JavaProxy \"java.lang.Runnable\" $handler)) ; Create proxy \n" +
                            "     (JavaCall $proxy \"run\"))")); // Call run() on proxy -> evaluates handler -> Is<String>


            // --- [8] Forgetting & Maintenance (Java logic, potentially configurable via MeTTa) ---
            printSectionHeader(8, "Forgetting & Maintenance");
            System.out.println("Adding temporary atoms...");
            for (var i = 0; i < 50; i++) cog.add(cog.S("Temp_" + UUID.randomUUID().toString().substring(0, 6)));
            var sizeBefore = cog.mem.size();
            System.out.println("Memory size before maintenance: " + sizeBefore);
            System.out.println("Waiting for maintenance cycle...");
            Thread.sleep(FORGETTING_CHECK_INTERVAL_MS + 2000); // Wait for scheduler
            var sizeAfter = cog.mem.size();
            System.out.println("Memory size after maintenance: " + sizeAfter + " (Target ~" + (sizeBefore * FORGETTING_TARGET_MEM_SIZE_FACTOR / 100) + ")");


        } catch (Exception e) {
            System.err.println("\n--- ERROR during demonstration ---");
            e.printStackTrace();
        } finally {
            cog.shutdown();
        }
        System.out.println("\n--- Cog Synthesized Test Suite Finished ---");
    }

    // --- Helper Methods for Demo ---
    private static void printSectionHeader(int sectionNum, String title) {
        System.out.printf("\n--- [%d] %s ---\n", sectionNum, title);
    }

    private static void testUnification(Cog cog, String name, Atom p, Atom i) {
        System.out.printf("Unify (%s): %s with %s -> %s%n", name, p, i, cog.unify.unify(p, i, Bind.EMPTY).map(Bind::toString).orElse("Failure"));
    }

    private static void evaluateAndPrint(Cog cog, String name, Atom expr) {
        System.out.println("eval \"" + name + "\" \t " + expr);
        var results = cog.eval(expr);
        System.out.printf(" -> [%s]%n", results.stream().map(Atom::toString).collect(Collectors.joining(", ")));
    }

    private static void printQueryResults(List<Answer> results) {
        if (results.isEmpty()) System.out.println(" -> No matches found.");
        else results.forEach(qr -> System.out.println(" -> Match: " + qr.resultAtom() + " | Binds: " + qr.bind));
    }
    // Helper for unitize (if not imported)
    // public static double unitize(double v) { return Math.max(0.0, Math.min(1.0, v)); }

    // Dummy unitize function if not available elsewhere
    public static double unitize(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    // --- Public API Methods ---
    public Atom parse(String metta) {
        return parser.parse(metta);
    }

    public List<Atom> load(String mettaCode) {
        return parser.parseAll(mettaCode).stream().map(this::add).toList();
    }

    public Atom S(String name) {
        return mem.sym(name);
    }

    public Var V(String name) {
        return mem.var(name);
    }

    public Expr E(Atom... children) {
        return mem.expr(List.of(children));
    }

    public Expr E(List<Atom> children) {
        return mem.expr(children);
    }

    public <T> Is<T> IS(T value) {
        return mem.is(value);
    }

    public Is<Function<List<Atom>, Optional<Atom>>> IS(String name, Function<List<Atom>, Optional<Atom>> fn) {
        return mem.isFn(name, fn);
    }

    public long getLogicalTime() {
        return logicalTime.get();
    }

    public long tick() {
        return logicalTime.incrementAndGet();
    }

    public <A extends Atom> A add(A atom) {
        return mem.add(atom);
    }

    public Expr EQ(Atom premise, Atom conclusion) {
        return add(E(SYMBOL_EQ, premise, conclusion));
    }

    public Expr TYPE(Atom instance, Atom type) {
        return add(E(SYMBOL_COLON, instance, type));
    }

    public List<Atom> eval(Atom expr) {
        return interp.eval(expr, INTERPRETER_DEFAULT_MAX_DEPTH);
    }

    public List<Atom> eval(Atom expr, int maxDepth) {
        return interp.eval(expr, maxDepth);
    }

    public Optional<Atom> evalBest(Atom expr) {
        return eval(expr).stream().max(Comparator.comparingDouble(a -> mem.valueOrDefault(a).getWeightedTruth()));
    }

    public List<Answer> query(Atom pattern) {
        return mem.query(pattern);
    }

    /**
     * Runs the agent loop within a specified environment and goal.
     */
    public void runAgent(Game env, Atom goal, int maxCycles) {
        this.environment = env; // Make environment accessible to Is functions
        agent.run(env, goal, maxCycles); // Delegate
        this.environment = null; // Clear reference
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) scheduler.shutdownNow();
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        System.out.println("Cog scheduler shut down.");
    }

    /**
     * Performs periodic maintenance (decay and forgetting). Internal use by scheduler.
     */
    private void performMaintenance() {
        mem.decayAndForget();
    }

    /**
     * Retrieve the current game environment (used by Is functions).
     */
    public Optional<Game> env() {
        return Optional.ofNullable(environment);
    }

    // --- Core Data Structures (Records, Sealed Interfaces - Generally stable) ---
    public enum AtomType {SYM, VAR, EXPR, IS}

    public sealed interface Atom {
        String id();

        AtomType type();

        default Sym asSym() {
            return (Sym) this;
        }

        default Var asVar() {
            return (Var) this;
        }

        default Expr asExpr() {
            return (Expr) this;
        }

        default Is<?> asIs() {
            return (Is<?>) this;
        }

        @Override
        boolean equals(Object other);

        @Override
        int hashCode();

        @Override
        String toString();
    }

    public interface Game { // Environment interface
        List<Atom> perceive(Cog cog);

        List<Atom> actions(Cog cog, Atom currentState);

        Act exe(Cog cog, Atom action);

        boolean isRunning();
    }

    public record Sym(String name) implements Atom {
        private static final ConcurrentMap<String, Sym> SYMBOL_CACHE = new ConcurrentHashMap<>();

        public static Sym of(String name) {
            return SYMBOL_CACHE.computeIfAbsent(name, Sym::new);
        }

        @Override
        public String id() {
            return name;
        }

        @Override
        public AtomType type() {
            return AtomType.SYM;
        }

        @Override
        public String toString() {
            return name;
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof Sym(var n1) && name.equals(n1));
        }
    }

    public record Var(String name) implements Atom {
        public Var {
            if (name.startsWith(VAR_PREFIX)) throw new IllegalArgumentException("Var name excludes prefix");
        }

        @Override
        public String id() {
            return VAR_PREFIX + name;
        }

        @Override
        public AtomType type() {
            return AtomType.VAR;
        }

        @Override
        public String toString() {
            return id();
        }

        @Override
        public int hashCode() {
            return id().hashCode();
        }

        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof Var v && id().equals(v.id()));
        }
    }

    public record Expr(String id, List<Atom> children) implements Atom {
        // Optimization: Cache computed IDs based on children list content identity
        private static final ConcurrentMap<List<Atom>, String> idCache = new ConcurrentHashMap<>();

        // Use List.copyOf to ensure immutable list for caching and internal state
        public Expr(List<Atom> inputChildren) {
            this(idCache.computeIfAbsent(List.copyOf(inputChildren), Expr::computeIdInternal), List.copyOf(inputChildren));
        }

        private static String computeIdInternal(List<Atom> childList) {
            return "(" + childList.stream().map(Atom::id).collect(Collectors.joining(" ")) + ")";
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public AtomType type() {
            return AtomType.EXPR;
        }

        public @Nullable Atom head() {
            return children.isEmpty() ? null : children.getFirst();
        }

        public List<Atom> tail() {
            return children.isEmpty() ? emptyList() : children.subList(1, children.size());
        }

        @Override
        public String toString() {
            return id;
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        // Use ID for equality check, assuming ID computation is robust and unique
        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof Expr ea && id.equals(ea.id));
        }
    }

    public record Is<T>(String id, @Nullable T value,
                        @Nullable Function<List<Atom>, Optional<Atom>> fn) implements Atom {
        public Is(T value) {
            this(deriveId(value), value, null);
        }

        public Is(String id, T value) {
            this(id, value, null);
        }

        public Is(String name, Function<List<Atom>, Optional<Atom>> fn) {
            this(name, null, fn);
        }

        // Derive a reasonably stable ID for Is atoms holding values
        private static <T> String deriveId(T value) {
            if (value == null) return "is:null:null";
            String typeName;
            String valuePart;
            if (value instanceof Function<?, ?>)
                return "is:Function:" + value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value)); // Special case for functions
            var clazz = value.getClass();
            typeName = clazz.getSimpleName();
            if (value instanceof String s) {
                typeName = "String";
                valuePart = s.length() < 30 ? s : s.substring(0, 27) + "...";
            } else if (value instanceof Number) {
                typeName = "Number";
                valuePart = value.toString();
            } // Keep full number string
            else if (value instanceof Boolean) {
                typeName = "Boolean";
                valuePart = value.toString();
            } else if (value instanceof Atom a) {
                typeName = "Atom";
                valuePart = a.id();
            } // Use atom's ID
            else if (value instanceof List<?> list) {
                typeName = "List";
                valuePart = "[" + list.stream().map(o -> o instanceof Atom a ? a.id() : String.valueOf(o)).limit(3).collect(Collectors.joining(",")) + (list.size() > 3 ? ",..." : "") + "]";
            } else {
                var valStr = value.toString();
                valuePart = (valStr.length() < 30) ? valStr : clazz.getName() + "@" + Integer.toHexString(value.hashCode());
            }
            return "is:" + typeName + ":" + valuePart;
        }

        @Override
        public AtomType type() {
            return AtomType.IS;
        }

        public boolean isFn() {
            return fn != null;
        }

        public Optional<Atom> apply(List<Atom> args) {
            return isFn() ? fn.apply(args) : empty();
        }

        @Override
        public String toString() {
            if (isFn()) return "IsFn<" + id + ">";
            if (value instanceof String s)
                return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\""; // Proper string escaping
            if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
            if (value instanceof Atom a) return a.toString(); // Show wrapped Atom directly
            if (value instanceof List<?> list && !list.isEmpty() && list.stream().allMatch(o -> o instanceof Atom)) {
                return "Is<List:" + list.stream().map(Object::toString).limit(5).collect(Collectors.joining(",", "[", list.size() > 5 ? ",...]" : "]")) + ">";
            }
            // Generic fallback for other Java objects wrapped in Is
            var valStr = String.valueOf(value);
            var typeName = value != null ? value.getClass().getSimpleName() : "null";
            return "Is<" + typeName + ":" + (valStr.length() > 20 ? valStr.substring(0, 17) + "..." : valStr) + ">";
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof Is<?> ga && id.equals(ga.id));
        }
    }

    // --- Metadata Records (Java implementation of Pri/Truth logic likely retained for performance) ---
    public record Value(Truth truth, Pri pri, Time access) {
        public static final Value DEFAULT = new Value(Truth.UNKNOWN, Pri.DEFAULT, Time.DEFAULT);

        public Value withTruth(Truth nt) {
            return new Value(nt, pri, access);
        }

        public Value withPri(Pri np) {
            return new Value(truth, np, access);
        }

        public Value withTime(Time na) {
            return new Value(truth, pri, na);
        }

        public Value updateTime(long now) {
            return new Value(truth, pri, new Time(now));
        }

        public Value boost(double amount, long now) {
            return withPri(pri.boost(amount)).updateTime(now);
        }

        public Value decay(long now) {
            return withPri(pri.decay()).updateTime(now);
        } // Update time on decay too

        public double getCurrentPri(long now) {
            var timeFactor = Math.exp(-Math.max(0, now - access.time()) / (FORGETTING_CHECK_INTERVAL_MS * 3.0));
            return pri.getCurrent(timeFactor) * truth.confidence();
        }

        public double getWeightedTruth() {
            return truth.confidence() * truth.strength;
        }

        @Override
        public String toString() {
            return truth + " " + pri + " " + access;
        }
    }

    public record Truth(double strength, double count) {
        public static final Truth TRUE = new Truth(1, 10.0);
        public static final Truth FALSE = new Truth(0, 10.0);
        public static final Truth UNKNOWN = new Truth(0.5, 0.1);

        public Truth {
            strength = unitize(strength);
            count = Math.max(0, count);
        }

        public double confidence() {
            return count / (count + TRUTH_DEFAULT_SENSITIVITY);
        }

        public Truth merge(Truth other) {
            if (other == null || other.count == 0) return this;
            if (this.count == 0) return other;
            var totalCount = this.count + other.count;
            var mergedStrength = (this.strength * this.count + other.strength * other.count) / totalCount;
            return new Truth(mergedStrength, totalCount);
        }

        @Override
        public String toString() {
            return String.format("%%%.3f,%.2f%%", strength, count);
        }
    }

    public record Pri(double sti, double lti) { // Pri mechanics kept in Java for performance (per guidelines)
        public static final Pri DEFAULT = new Pri(PRI_INITIAL_STI, PRI_INITIAL_STI * PRI_INITIAL_LTI_FACTOR);

        public Pri {
            sti = unitize(sti);
            lti = unitize(lti);
        }

        public Pri decay() {
            var decayedSti = sti * (1 - PRI_STI_DECAY_RATE);
            var ltiGain = sti * PRI_STI_DECAY_RATE * PRI_STI_TO_LTI_RATE;
            var decayedLti = lti * (1 - PRI_LTI_DECAY_RATE) + ltiGain;
            return new Pri(unitize(decayedSti), unitize(decayedLti)); // Ensure unitized values
        }

        public Pri boost(double amount) {
            if (amount <= 0) return this;
            var boostedSti = unitize(sti + amount);
            var ltiBoostFactor = PRI_STI_TO_LTI_RATE * Math.abs(amount); // Use abs(amount) for factor? Or just amount? Let's stick to amount.
            var boostedLti = unitize(lti + (boostedSti - sti) * ltiBoostFactor); // Boost LTI based on STI gain
            return new Pri(boostedSti, boostedLti);
        }

        public double getCurrent(double recencyFactor) {
            return sti * recencyFactor * 0.6 + lti * 0.4;
        }

        @Override
        public String toString() {
            return String.format("$%.3f,%.3f", sti, lti);
        }
    }

    // --- Core Engine Components ---

    public record Time(long time) {
        public static final Time DEFAULT = new Time(0L);

        @Override
        public String toString() {
            return "@" + time;
        }
    }

    /**
     * Manages Atom storage, indexing, metadata, and forgetting. Java implementation is performance-critical.
     */
    public static class Mem {
        private final ConcurrentMap<String, Atom> atomsById = new ConcurrentHashMap<>(1024);
        private final ConcurrentMap<Atom, AtomicReference<Value>> storage = new ConcurrentHashMap<>(1024);
        private final ConcurrentMap<Atom, ConcurrentSkipListSet<String>> indexByHead = new ConcurrentHashMap<>();
        private final Supplier<Long> timeSource;

        public Mem(Supplier<Long> timeSource) {
            this.timeSource = timeSource;
        }

        @SuppressWarnings("unchecked")
        public <A extends Atom> A add(A atom) {
            // Ensure canonical instance is used/created
            var canonicalAtom = (A) atomsById.computeIfAbsent(atom.id(), id -> atom);
            long now = timeSource.get();
            // Compute or update value; boost slightly on access/add
            var valueRef = storage.computeIfAbsent(canonicalAtom, k -> {
                var initVal = Value.DEFAULT.withPri(new Pri(PRI_INITIAL_STI, PRI_INITIAL_STI * PRI_INITIAL_LTI_FACTOR)).updateTime(now);
                updateIndices(k);
                return new AtomicReference<>(initVal);
            });
            valueRef.updateAndGet(v -> v.boost(PRI_BOOST_ON_ACCESS * 0.1, now));
            // Async check for memory pressure
            checkMemoryAndTriggerForgetting();
            return canonicalAtom;
        }

        public Optional<Atom> getAtom(String id) {
            return Optional.ofNullable(atomsById.get(id)).map(this::boostAndGet);
        }

        public Optional<Atom> getAtom(Atom atom) {
            return Optional.ofNullable(atomsById.get(atom.id())).map(this::boostAndGet);
        }

        private Atom boostAndGet(Atom atom) {
            updateValue(atom, v -> v.boost(PRI_BOOST_ON_ACCESS, timeSource.get()));
            return atom;
        }

        public Optional<Value> value(Atom atom) {
            return Optional.ofNullable(atomsById.get(atom.id())).flatMap(key -> Optional.ofNullable(storage.get(key))).map(AtomicReference::get);
        }

        public Value valueOrDefault(Atom atom) {
            return value(atom).orElse(Value.DEFAULT);
        }

        public void updateValue(Atom atom, Function<Value, Value> updater) {
            var canonicalAtom = atomsById.get(atom.id());
            if (canonicalAtom == null) return;
            var valueRef = storage.get(canonicalAtom);
            if (valueRef != null) {
                long now = timeSource.get();
                valueRef.updateAndGet(current -> {
                    var updated = updater.apply(current).updateTime(now); // Always update time
                    var confDiff = updated.truth.confidence() - current.truth.confidence();
                    // Boost significantly if confidence increased substantially
                    var boost = (confDiff > TRUTH_REVISION_CONFIDENCE_THRESHOLD) ? PRI_BOOST_ON_REVISION_MAX * updated.truth.confidence() : 0.0;
                    return boost > 0 ? updated.boost(boost, now) : updated;
                });
            }
        }

        public void updateTruth(Atom atom, Truth newTruth) {
            updateValue(atom, v -> v.withTruth(v.truth.merge(newTruth)));
        }

        public void boost(Atom atom, double amount) {
            updateValue(atom, v -> v.boost(amount, timeSource.get()));
        }

        public Atom sym(String name) {
            return add(Sym.of(name));
        }

        public Var var(String name) {
            return add(new Var(name));
        }

        public Expr expr(List<Atom> children) {
            return add(new Expr(children));
        }

        public Expr expr(Atom... children) {
            return add(new Expr(List.of(children)));
        }

        public <T> Is<T> is(T value) {
            return add(new Is<>(value));
        }

        public <T> Is<T> is(String id, T value) {
            return add(new Is<>(id, value));
        }

        public Is<Function<List<Atom>, Optional<Atom>>> isFn(String name, Function<List<Atom>, Optional<Atom>> fn) {
            Is<Function<List<Atom>, Optional<Atom>>> i = new Is<>(name, fn);
            add(i); // Add the IsFn atom itself to memory
            return i;
        }

        /**
         * Performs pattern matching query. Uses Java Streams and Unify for performance.
         */
        public List<Answer> query(Atom pattern) {
            var queryPattern = add(pattern);
            boost(queryPattern, PRI_BOOST_ON_ACCESS * 0.2);
            Stream<Atom> candidateStream;
            // Optimize candidate selection based on pattern head if possible
            if (queryPattern instanceof Expr pExpr && pExpr.head() != null && !(pExpr.head() instanceof Var)) {
                candidateStream = indexByHead.getOrDefault(pExpr.head(), new ConcurrentSkipListSet<>()).stream()
                        .map(this::getAtom).flatMap(Optional::stream);
                // Always include the pattern itself if it's a ground term already in memory
                if (value(queryPattern).isPresent())
                    candidateStream = Stream.concat(candidateStream, Stream.of(queryPattern));
            } else if (queryPattern instanceof Var) {
                candidateStream = storage.keySet().stream(); // Matches all, potentially slow
            } else { // Sym or Is direct check, or Expr with Var head
                candidateStream = Stream.of(queryPattern).filter(storage::containsKey);
                // If pattern is Expr with Var head, also consider all expressions (slow) - potential optimization needed here
                if (queryPattern instanceof Expr pExpr && pExpr.head() instanceof Var) {
                    candidateStream = Stream.concat(candidateStream, storage.keySet().stream().filter(a -> a.type() == AtomType.EXPR));
                }
            }

            List<Answer> results = new ArrayList<>();
            var unification = new Unify(this);
            var checkCount = 0;
            var maxChecks = Math.min(5000 + size() / 10, 15000); // Limit checks
            var it = candidateStream.distinct().iterator(); // Avoid duplicate checks
            while (it.hasNext() && results.size() < INTERPRETER_MAX_RESULTS && checkCount < maxChecks) {
                var candidate = it.next();
                checkCount++;
                // Filter low-confidence candidates early
                if (valueOrDefault(candidate).truth.confidence() < TRUTH_MIN_CONFIDENCE_MATCH) continue;
                // Perform unification
                unification.unify(queryPattern, candidate, Bind.EMPTY).ifPresent(bind -> {
                    boost(candidate, PRI_BOOST_ON_ACCESS); // Boost matched atom
                    results.add(new Answer(candidate, bind));
                });
            }
            // Sort results by weighted truth of the matched atom
            results.sort(Comparator.comparingDouble((Answer ans) -> valueOrDefault(ans.resultAtom()).getWeightedTruth()).reversed());
            return results.stream().limit(INTERPRETER_MAX_RESULTS).toList();
        }

        /**
         * Decays Pri and removes low-Pri atoms. Core logic in Java for performance. Configurable thresholds could be MeTTa-loaded.
         */
        synchronized void decayAndForget() {
            final long now = timeSource.get();
            var initialSize = storage.size();
            if (initialSize == 0) return;
            final var currentForgetThreshold = PRI_MIN_FORGET_THRESHOLD; // Could be MeTTa config
            final var currentMaxMemTrigger = FORGETTING_MAX_MEM_SIZE_TRIGGER; // Could be MeTTa config
            final var currentTargetFactor = FORGETTING_TARGET_MEM_SIZE_FACTOR; // Could be MeTTa config

            List<Atom> candidatesForRemoval = new ArrayList<>();
            var decayCount = 0;
            // Iterate and decay all atoms, collecting potential removal candidates
            for (var entry : storage.entrySet()) {
                var atom = entry.getKey();
                var valueRef = entry.getValue();
                var isProtected = atom instanceof Var || (atom instanceof Sym s && PROTECTED_SYMBOLS.contains(s.name()));
                var decayedValue = valueRef.updateAndGet(v -> v.decay(now));
                decayCount++;
                if (!isProtected && decayedValue.getCurrentPri(now) < currentForgetThreshold) {
                    candidatesForRemoval.add(atom);
                }
            }
            var removedCount = 0;
            var targetSize = currentMaxMemTrigger * currentTargetFactor / 100;
            var memoryPressure = initialSize > currentMaxMemTrigger;
            var significantLowPri = candidatesForRemoval.size() > initialSize * 0.05; // Remove if >5% are low pri

            // Perform removal if needed based on thresholds or memory pressure
            if (!candidatesForRemoval.isEmpty() && (memoryPressure || significantLowPri || initialSize > targetSize)) {
                // Sort candidates by priority (lowest first)
                candidatesForRemoval.sort(Comparator.comparingDouble(atom -> valueOrDefault(atom).getCurrentPri(now)));
                var removalTargetCount = memoryPressure ? Math.max(0, initialSize - targetSize) : candidatesForRemoval.size();
                var actuallyRemoved = 0;
                for (var atomToRemove : candidatesForRemoval) {
                    if (actuallyRemoved >= removalTargetCount) break;
                    // Re-check threshold in case priority changed concurrently (unlikely with synchronized method)
                    if (valueOrDefault(atomToRemove).getCurrentPri(now) < currentForgetThreshold) {
                        if (removeAtomInternal(atomToRemove)) actuallyRemoved++;
                    }
                }
                removedCount = actuallyRemoved;
            }
            if (removedCount > 0 || (decayCount > 0 && initialSize > 10)) {
                System.out.printf("Memory Maintenance: Decayed %d atoms. Removed %d low-pri atoms (Threshold=%.3f). Size %d -> %d.%n",
                        decayCount, removedCount, currentForgetThreshold, initialSize, storage.size());
            }
        }

        boolean removeAtomInternal(Atom atom) {
            // Remove from primary storage, ID lookup, and indices
            if (storage.remove(atom) != null) {
                atomsById.remove(atom.id());
                removeIndices(atom);
                return true;
            }
            return false;
        }

        private void checkMemoryAndTriggerForgetting() {
            // Trigger async if memory exceeds threshold
            if (storage.size() > FORGETTING_MAX_MEM_SIZE_TRIGGER) CompletableFuture.runAsync(this::decayAndForget);
        }

        private void updateIndices(Atom atom) {
            // Index expressions by their head symbol/atom for faster query candidate selection
            if (atom instanceof Expr e && e.head() != null && !(e.head() instanceof Var)) { // Only index if head is not a var
                indexByHead.computeIfAbsent(e.head(), h -> new ConcurrentSkipListSet<>()).add(atom.id());
            }
        }

        private void removeIndices(Atom atom) {
            // Remove from index when atom is removed
            if (atom instanceof Expr e && e.head() != null && !(e.head() instanceof Var)) {
                indexByHead.computeIfPresent(e.head(), (k, v) -> {
                    v.remove(atom.id());
                    return v.isEmpty() ? null : v;
                });
            }
        }

        public int size() {
            return storage.size();
        }
    }

    /**
     * Represents unification bindings. Kept as Java record.
     */
    public record Bind(Map<Var, Atom> map) {
        public static final Bind EMPTY = new Bind(emptyMap());

        public Bind {
            map = Map.copyOf(map);
        } // Ensure immutability

        public boolean isEmpty() {
            return map.isEmpty();
        }

        public Optional<Atom> get(Var var) {
            return Optional.ofNullable(map.get(var));
        }

        /**
         * Recursively resolves variable binding, handles cycles.
         */
        public Optional<Atom> getRecursive(Var var) {
            var current = map.get(var);
            if (current == null) return empty();
            Set<Var> visited = new HashSet<>();
            visited.add(var);
            while (current instanceof Var v) {
                if (!visited.add(v)) return empty(); // Cycle detected
                var next = map.get(v);
                if (next == null) return Optional.of(v); // Unbound var in chain
                current = next;
            }
            return Optional.of(current); // Resolved to a non-var atom
        }

        @Override
        public String toString() {
            return map.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", ", "{", "}"));
        }
    }

    /**
     * Represents a query answer. Kept as Java record.
     */
    public record Answer(Atom resultAtom, Bind bind) {
    }

    /**
     * Performs unification. Core algorithm kept in Java for performance.
     */
    public static class Unify {
        private final Mem space;

        public Unify(Mem space) {
            this.space = space;
        }

        /**
         * Unification logic (iterative stack-based approach).
         */
        public Optional<Bind> unify(Atom pattern, Atom instance, Bind initialBind) {
            Deque<Pair<Atom, Atom>> stack = new ArrayDeque<>();
            stack.push(new Pair<>(pattern, instance));
            var currentBinds = initialBind;
            while (!stack.isEmpty()) {
                var task = stack.pop();
                // Substitute *before* comparison/processing
                var p = subst(task.a, currentBinds);
                var i = subst(task.b, currentBinds);
                if (p.equals(i)) continue; // Trivial match or already unified
                if (p instanceof Var pVar) { // Pattern variable
                    if (containsVar(i, pVar)) return empty(); // Occurs check failure
                    var updatedBinds = mergeBind(currentBinds, pVar, i);
                    if (updatedBinds == null) return empty(); // Merge failed (conflict)
                    currentBinds = updatedBinds;
                    continue;
                }
                if (i instanceof Var iVar) { // Instance variable (can happen if matching patterns)
                    if (containsVar(p, iVar)) return empty(); // Occurs check failure
                    var updatedBinds = mergeBind(currentBinds, iVar, p);
                    if (updatedBinds == null) return empty(); // Merge failed
                    currentBinds = updatedBinds;
                    continue;
                }
                if (p instanceof Expr pExpr && i instanceof Expr iExpr) { // Both expressions
                    var pChildren = pExpr.children();
                    var iChildren = iExpr.children();
                    var pSize = pChildren.size();
                    if (pSize != iChildren.size()) return empty(); // Arity mismatch
                    // Push children pairs in reverse order for LIFO stack processing
                    for (var j = pSize - 1; j >= 0; j--) stack.push(new Pair<>(pChildren.get(j), iChildren.get(j)));
                    continue;
                }
                // Mismatch (Sym vs Expr, different Syms, Is vs non-Is, different Is values)
                return empty();
            }
            return Optional.of(currentBinds); // Success
        }

        /**
         * Applies bindings recursively to an atom.
         */
        public Atom subst(Atom atom, Bind bind) {
            if (bind.isEmpty()) return atom; // Quick exit if no bindings
            return switch (atom) {
                case Var var ->
                        bind.getRecursive(var).map(val -> subst(val, bind)).orElse(var); // Recursively substitute bound value or return var if unbound/cycle
                case Expr expr -> {
                    var changed = false;
                    List<Atom> newChildren = new ArrayList<>(expr.children().size());
                    for (var child : expr.children()) {
                        var substChild = subst(child, bind);
                        if (child != substChild) changed = true; // Reference equality check is sufficient here
                        newChildren.add(substChild);
                    }
                    // Create new Expr only if children changed, preserving canonical instances
                    yield changed ? space.expr(newChildren) : expr; // Use space.expr to get canonical version
                }
                default -> atom; // Sym, Is are constants w.r.t. substitution
            };
        }

        /**
         * Occurs check helper: checks if 'var' occurs within 'expr'.
         */
        private boolean containsVar(Atom expr, Var var) {
            return switch (expr) {
                case Var v -> v.equals(var);
                case Expr e -> e.children().stream().anyMatch(c -> containsVar(c, var));
                default -> false;
            };
        }

        /**
         * Merges a new binding (var=value) into existing bindings. Handles conflicts via unification. Returns null on conflict.
         */
        private @Nullable Bind mergeBind(Bind current, Var var, Atom value) {
            var existingBindOpt = current.getRecursive(var);
            if (existingBindOpt.isPresent()) { // Var already bound or resolves to something?
                // Unify the existing binding with the new value. If they unify, the result contains the merged bindings.
                return unify(existingBindOpt.get(), value, current).orElse(null); // Return null on unification conflict
            } else { // New binding
                var m = new HashMap<>(current.map());
                m.put(var, value);
                return new Bind(m);
            }
        }
    }

    /**
     * Parses MeTTa text into Atoms. Kept as Java implementation for performance.
     */
    private static class MettaParser {
        private final Cog cog;

        MettaParser(Cog cog) {
            this.cog = cog;
        }

        // Simplified handling for core symbols during parsing
        private Atom parseSymbolOrIs(String text) {
            return switch (text) {
                case "True" -> SYMBOL_TRUE;
                case "False" -> SYMBOL_FALSE;
                case "Nil" -> SYMBOL_NIL;
                // Basic number parsing (can be extended)
                default -> {
                    try {
                        yield cog.IS(Double.parseDouble(text));
                    } // Try parsing as Double first
                    catch (NumberFormatException e) {
                        yield cog.S(text);
                    } // Fallback to Symbol
                }
            };
        }

        private String unescapeString(String s) {
            return s.replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\");
        }

        // Tokenizer state machine
        private List<Token> tokenize(String text) {
            List<Token> tokens = new ArrayList<>();
            var line = 1;
            var col = 1;
            var i = 0;
            final var len = text.length();
            while (i < len) {
                var c = text.charAt(i);
                var startCol = col;
                if (Character.isWhitespace(c)) {
                    if (c == '\n') {
                        line++;
                        col = 1;
                    } else col++;
                    i++;
                } // Whitespace
                else if (c == '(') {
                    tokens.add(new Token(TokenType.LPAREN, "(", line, startCol));
                    i++;
                    col++;
                } else if (c == ')') {
                    tokens.add(new Token(TokenType.RPAREN, ")", line, startCol));
                    i++;
                    col++;
                } else if (c == ';') { // Comment
                    var start = i;
                    while (i < len && text.charAt(i) != '\n') i++;
                    // Optionally store comment token: tokens.add(new Token(TokenType.COMMENT, text.substring(start, i), line, startCol));
                    if (i < len && text.charAt(i) == '\n') {
                        line++;
                        col = 1;
                        i++;
                    } else col = 1; // Reset col after comment or at EOF
                } else if (c == '"') { // String literal
                    var start = i;
                    i++;
                    col++;
                    var sb = new StringBuilder();
                    var escaped = false;
                    while (i < len) {
                        var nc = text.charAt(i);
                        if (nc == '"' && !escaped) {
                            i++;
                            col++;
                            tokens.add(new Token(TokenType.STRING, text.substring(start, i), line, startCol));
                            break;
                        }
                        if (nc == '\n')
                            throw new MettaParseException("Unterminated string at line " + line + ":" + startCol);
                        sb.append(nc);
                        escaped = (nc == '\\' && !escaped);
                        i++;
                        col++;
                    }
                    if (i == len && (tokens.isEmpty() || tokens.getLast().type != TokenType.STRING))
                        throw new MettaParseException("Unterminated string at EOF");
                } else if (c == VAR_PREFIX.charAt(0)) { // Variable
                    var start = i;
                    i++;
                    col++;
                    while (i < len && !Character.isWhitespace(text.charAt(i)) && !"();".contains(String.valueOf(text.charAt(i)))) {
                        i++;
                        col++;
                    }
                    var varName = text.substring(start, i);
                    if (varName.length() <= VAR_PREFIX.length())
                        throw new MettaParseException("Invalid var name '" + varName + "' at line " + line + ":" + startCol);
                    tokens.add(new Token(TokenType.VAR, varName, line, startCol));
                } else { // Symbol or potential Number
                    var start = i;
                    // Greedily consume non-whitespace, non-delimiter characters
                    while (i < len && !Character.isWhitespace(text.charAt(i)) && !"();".contains(String.valueOf(text.charAt(i)))) {
                        i++;
                        col++;
                    }
                    var value = text.substring(start, i);
                    // Attempt to parse as number, fallback to symbol - refined in parseAtomFromTokens now
                    tokens.add(new Token(TokenType.SYMBOL, value, line, startCol)); // Initially mark as SYMBOL
                }
            }
            return tokens;
        }

        // Recursive descent parser
        private Atom parseAtomFromTokens(PeekableIterator<Token> it) {
            if (!it.hasNext()) throw new MettaParseException("Unexpected EOF during parsing");
            var token = it.next();
            return switch (token.type) {
                case LPAREN -> parseExprFromTokens(it);
                case VAR -> cog.V(token.text.substring(VAR_PREFIX.length())); // Extract name without prefix
                case SYMBOL -> parseSymbolOrIs(token.text); // Handles True/False/Nil/Numbers/Symbols
                case STRING -> cog.IS(unescapeString(token.text.substring(1, token.text.length() - 1)));
                case RPAREN -> throw new MettaParseException("Unexpected ')' at line " + token.line + ":" + token.col);
                // Skip comments implicitly by tokenizer or handle explicitly if comments are tokenized
                case COMMENT -> parseAtomFromTokens(it); // Skip comment token and parse next
                // NUMBER type removed, handled by SYMBOL -> parseSymbolOrIs
                case EOF ->
                        throw new MettaParseException("Internal Error: Reached EOF token during parsing"); // Should be caught by hasNext
            };
        }

        private Expr parseExprFromTokens(PeekableIterator<Token> it) {
            List<Atom> children = new ArrayList<>();
            while (true) {
                if (!it.hasNext()) throw new MettaParseException("Unterminated expression, missing ')'");
                var next = it.peek();
                if (next.type == TokenType.RPAREN) {
                    it.next();
                    return cog.E(children);
                } // End of expression
                // Skip comments within expressions if they are tokenized
                if (next.type == TokenType.COMMENT) {
                    it.next();
                    continue;
                }
                children.add(parseAtomFromTokens(it)); // Parse child atom recursively
            }
        }

        public Atom parse(String text) {
            var tokens = tokenize(text).stream().filter(t -> t.type != TokenType.COMMENT).toList(); // Filter comments before parsing
            if (tokens.isEmpty()) throw new MettaParseException("Empty input or input contains only comments");
            var it = new PeekableIterator<>(tokens.iterator());
            var result = parseAtomFromTokens(it);
            if (it.hasNext())
                throw new MettaParseException("Extra token after parsing: " + it.next()); // Ensure all tokens consumed
            return result;
        }

        public List<Atom> parseAll(String text) {
            var tokens = tokenize(text).stream().filter(t -> t.type != TokenType.COMMENT).toList(); // Filter comments
            List<Atom> results = new ArrayList<>();
            var it = new PeekableIterator<>(tokens.iterator());
            while (it.hasNext()) {
                results.add(parseAtomFromTokens(it));
            }
            return results;
        }

        // Token types, including COMMENT if needed later
        private enum TokenType {LPAREN, RPAREN, SYMBOL, VAR, STRING, COMMENT, EOF}

        private record Token(TokenType type, String text, int line, int col) {
        }

        // Simple peekable iterator helper
        private static class PeekableIterator<T> implements Iterator<T> {
            private final Iterator<T> iterator;
            private @Nullable T nextElement;

            public PeekableIterator(Iterator<T> iterator) {
                this.iterator = iterator;
                advance();
            }

            @Override
            public boolean hasNext() {
                return nextElement != null;
            }

            @Override
            public T next() {
                if (!hasNext()) throw new NoSuchElementException();
                var current = nextElement;
                advance();
                return current;
            }

            public T peek() {
                if (!hasNext()) throw new NoSuchElementException();
                return nextElement;
            }

            private void advance() {
                nextElement = iterator.hasNext() ? iterator.next() : null;
            }
        }
    }

    public static class MettaParseException extends RuntimeException {
        public MettaParseException(String message) {
            super(message);
        }
    }

    private record Pair<A, B>(A a, B b) {
    } // General purpose pair record


    /**
     * Initializes core symbols, Is functions, and loads initial bootstrap MeTTa rules.
     */
    private static class Core {
        // Core MeTTa rules loaded at startup. Defines basic types and links operators to Is functions.
        private static final String CORE_METTA_RULES = """
                    ; Basic Types Declaration (Self-description)
                    (: = Type) (: : Type) (: -> Type) (: Type Type)
                    (: True Type) (: False Type) (: Nil Type) (: Number Type) (: String Type)
                    (: Bool Type) (: Atom Type) (: List Type) (: JavaObject Type) (: Fn Type)
                    (: State Type) (: Action Type) (: Goal Type) (: Utility Type) (: Implies Type) (: Seq Type) (: Act Type)
                
                    ; Type Assertions for constants
                    (: True Bool) (: False Bool) (: Nil List)
                    ; Type assertions for core Is functions
                    (: match Fn) (: eval Fn) (: add-atom Fn) (: remove-atom Fn)
                    (: get-value Fn) (: &self Fn) (: _+ Fn) (: _- Fn) (: _* Fn)
                    (: _/ Fn) (: _== Fn) (: _> Fn) (: _< Fn) (: Concat Fn)
                    (: If Fn) (: Let Fn) (: IsEmpty Fn) (: IsNil Fn) (: RandomFloat Fn)
                    (: GetEnv Fn) (: AgentPerceive Fn) (: AgentAvailableActions Fn) (: AgentExecute Fn)
                    (: JavaNew Fn) (: JavaCall Fn) (: JavaStaticCall Fn) (: JavaField Fn) (: JavaProxy Fn)
                
                    ; Peano Arithmetic Example Rules (Illustrative)
                    (= (add Z $n) $n)
                    (= (add (S $m) $n) (S (add $m $n)))
                
                    ; Linking operators to internal Is function primitives
                    (= (+ $a $b) (_+ $a $b))
                    (= (- $a $b) (_- $a $b))
                    (= (* $a $b) (_* $a $b))
                    (= (/ $a $b) (_/ $a $b))
                    (= (== $a $b) (_== $a $b))
                    (= (> $a $b) (_> $a $b))
                    (= (< $a $b) (_< $a $b))
                
                    ; Basic List processing (Conceptual - requires more list Is functions like Cons, head, tail)
                    ; (= (head (Cons $h $t)) $h)
                    ; (= (tail (Cons $h $t)) $t)
                
                    ; Basic Boolean logic (Example)
                    ; (= (And True $x) $x)
                    ; (= (Not True) False)
                """;
        private final Cog cog;

        Core(Cog cog) {
            this.cog = cog;
            // Assign canonical symbols (retrieved or created via S)
            Cog.SYMBOL_EQ = cog.S("=");
            Cog.SYMBOL_COLON = cog.S(":");
            Cog.SYMBOL_ARROW = cog.S("->");
            Cog.SYMBOL_TYPE = cog.S("Type");
            Cog.SYMBOL_TRUE = cog.S("True");
            Cog.SYMBOL_FALSE = cog.S("False");
            Cog.SYMBOL_SELF = cog.S("Self");
            Cog.SYMBOL_NIL = cog.S("Nil");

            initIsFn(); // Define bridges to Java functionality (including JVM bridge calls)

            cog.load(CORE_METTA_RULES); // Load core rules from MeTTa string

            // Ensure core symbols/types have high confidence/pri
            Stream.of(SYMBOL_EQ, SYMBOL_COLON, SYMBOL_ARROW, SYMBOL_TYPE, SYMBOL_TRUE, SYMBOL_FALSE, SYMBOL_NIL, SYMBOL_SELF,
                            cog.S("Number"), cog.S("Bool"), cog.S("String"), cog.S("Atom"), cog.S("List"), cog.S("Act"),
                            cog.S("Fn"), cog.S("JavaObject")) // Add type for Java objects
                    .forEach(sym -> {
                        cog.mem.updateTruth(sym, Truth.TRUE);
                        cog.mem.boost(sym, 1.0);
                    });
        }

        // --- Helpers for Is Functions (Kept in Java for performance) ---
        // Extracts Double value from Is<Number> or by evaluating atom
        private static Optional<Double> numValue(@Nullable Atom atom, Cog cog) {
            Function<Atom, Optional<Double>> extractor = a ->
                    (a instanceof Is<?> g && g.value() instanceof Number n) ? Optional.of(n.doubleValue()) : empty();
            return Optional.ofNullable(atom).flatMap(a -> extractor.apply(a).or(() -> cog.evalBest(a).flatMap(extractor)));
        }

        // Extracts List<Atom> value from Is<List> or by evaluating atom
        static Optional<List<Atom>> listValue(@Nullable Atom atom) {
            return atom instanceof Is<?> g && g.value() instanceof List<?> l &&
                   l.stream().allMatch(i -> i instanceof Atom) ?
                        Optional.of((List<Atom>) l) : empty();
        }

        // Extracts String value from Is<String>, Sym, specific atoms, or by evaluating
        private static Optional<String> stringValue(@Nullable Atom atom) {
            Function<Atom, Optional<String>> extractor = a -> switch (a) {
                case Is<?> g when g.value() instanceof String s -> Optional.of(s);
                case Sym s -> Optional.of(s.name());
                case Is<?> g when g.value() instanceof Number n -> Optional.of(n.toString());
                case Atom k when k.equals(SYMBOL_TRUE) -> Optional.of("True");
                case Atom k when k.equals(SYMBOL_FALSE) -> Optional.of("False");
                case Atom k when k.equals(SYMBOL_NIL) -> Optional.of("Nil");
                default -> Optional.empty();
            };
            // Direct check first, then evaluate if needed (avoids unnecessary evaluation)
            return Optional.ofNullable(atom).flatMap(extractor); // No eval needed for string concat usually
        }

        // Applies a binary double operation to two arguments
        private static Optional<Atom> applyNumOp(List<Atom> args, Cog cog, BinaryOperator<Double> op) {
            if (args.size() != 2) return empty();
            var n1 = numValue(args.get(0), cog);
            var n2 = numValue(args.get(1), cog);
            return n1.isPresent() && n2.isPresent() ? Optional.of(cog.IS(op.apply(n1.get(), n2.get()))) : empty();
        }

        // Applies a binary double predicate to two arguments
        private static Optional<Atom> applyNumPred(List<Atom> args, Cog cog, BiPredicate<Double, Double> op) {
            if (args.size() != 2) return empty();
            var n1 = numValue(args.get(0), cog);
            var n2 = numValue(args.get(1), cog);
            return n1.isPresent() && n2.isPresent() ? Optional.of(op.test(n1.get(), n2.get()) ? SYMBOL_TRUE : SYMBOL_FALSE) : empty();
        }

        /**
         * Defines the `Is` Fn that bridge MeTTa to Java code.
         */
        private void initIsFn() {
            // --- Core Ops ---
            // (match space pattern template) -> Is<List<Result>>
            cog.IS("match", args -> (args.size() != 3) ? empty() :
                    Optional.ofNullable((args.getFirst() instanceof Is<?> g && g.value() instanceof Mem ts) ? ts : cog.mem) // Get space or default
                            .map(space -> cog.IS(space.query(args.get(1)).stream() // Perform query
                                    .map(ans -> cog.interp.subst(args.get(2), ans.bind())) // Substitute into template
                                    .toList())) // Collect results into Is<List<Atom>>
            );
            // (eval expr) -> Best result atom
            cog.IS("eval", args -> args.isEmpty() ? empty() : cog.evalBest(args.getFirst()));
            // (add-atom atom) -> Added atom
            cog.IS("add-atom", args -> args.isEmpty() ? empty() : Optional.of(cog.add(args.getFirst())));
            // (remove-atom atom) -> True/False
            cog.IS("remove-atom", args -> Optional.of((!args.isEmpty() && cog.mem.removeAtomInternal(args.getFirst())) ? SYMBOL_TRUE : SYMBOL_FALSE));
            // (get-value atom) -> Is<Value>
            cog.IS("get-value", args -> args.isEmpty() ? empty() : cog.mem.value(args.getFirst()).map(cog::IS));
            // (&self) -> Is<Mem> of current space
            cog.IS("&self", args -> Optional.of(cog.IS(cog.mem)));

            // --- Arithmetic / Comparison (Use internal primitives) ---
            cog.IS("_+", args -> applyNumOp(args, cog, Double::sum));
            cog.IS("_-", args -> applyNumOp(args, cog, (a, b) -> a - b));
            cog.IS("_*", args -> applyNumOp(args, cog, (a, b) -> a * b));
            cog.IS("_/", args -> applyNumOp(args, cog, (a, b) -> Math.abs(b) < 1e-12 ? Double.NaN : a / b)); // Handle division by zero
            cog.IS("_==", args -> applyNumPred(args, cog, (a, b) -> Math.abs(a - b) < 1e-9)); // Tolerance for float comparison
            cog.IS("_>", args -> applyNumPred(args, cog, (a, b) -> a > b));
            cog.IS("_<", args -> applyNumPred(args, cog, (a, b) -> a < b));

            // --- String Ops ---
            cog.IS("Concat", args -> Optional.of(cog.IS(args.stream().map(Core::stringValue).flatMap(Optional::stream).collect(Collectors.joining()))));

            // --- Control Flow / Util ---
            // If (evaluates condition, returns unevaluated branch)
            cog.IS("If", args -> (args.size() != 3) ? empty() :
                    cog.evalBest(args.getFirst()) // Evaluate condition
                            .map(condResult -> condResult.equals(SYMBOL_TRUE) ? args.get(1) : args.get(2)) // Return then/else branch unevaluated
            );
            // Let (evaluates value, binds var, returns substituted body unevaluated)
            cog.IS("Let", args -> {
                if (args.size() != 2 || !(args.get(0) instanceof Expr bindingExpr)
                        || !bindingExpr.head().equals(SYMBOL_EQ) || bindingExpr.children().size() != 3
                        || !(bindingExpr.children().get(1) instanceof Var varToBind)) {
                    return empty(); // Expects (Let (= $var <valueExpr>) <bodyExpr>)
                }
                var valueExpr = bindingExpr.children().get(2);
                var bodyExpr = args.get(1);
                return cog.evalBest(valueExpr).map(valueResult -> // Evaluate value expr
                        cog.unify.subst(bodyExpr, new Bind(Map.of(varToBind, valueResult))) // Substitute and return body
                );
            });
            // IsEmpty (evaluates arg, checks if Nil or Is<EmptyList>)
            cog.IS("IsEmpty", args -> args.isEmpty() ? Optional.of(SYMBOL_TRUE) :
                    cog.evalBest(args.getFirst()).map(res ->
                            res.equals(SYMBOL_NIL) || (res instanceof Is<?> g && g.value() instanceof List<?> l && l.isEmpty())
                                    ? SYMBOL_TRUE : SYMBOL_FALSE
                    ));
            // IsNil (evaluates arg, checks if Nil symbol)
            cog.IS("IsNil", args -> args.isEmpty() ? Optional.of(SYMBOL_TRUE) :
                    cog.evalBest(args.getFirst()).map(res -> res.equals(SYMBOL_NIL) ? SYMBOL_TRUE : SYMBOL_FALSE)
            );
            // RandomFloat [0,1)
            cog.IS("RandomFloat", args -> Optional.of(cog.IS(RandomGenerator.getDefault().nextDouble())));

            // --- Agent Support (Bridge to environment and agent state) ---
            // (GetEnv) -> Is<Game>
            cog.IS("GetEnv", args -> cog.env().map(cog::IS));
            // (AgentPerceive $agent) -> Is<List<PerceptAtoms>>
            cog.IS("AgentPerceive", args -> args.isEmpty() ? empty() :
                    cog.evalBest(args.getFirst()).flatMap(agentId -> // Requires agent ID (e.g., Self)
                            cog.env().map(env -> cog.IS(env.perceive(cog))) // Get percepts as Is<List<Atom>>
                    ));
            // (AgentAvailableActions $agent $state) -> Is<List<ActionAtoms>>
            cog.IS("AgentAvailableActions", args -> (args.size() != 2) ? empty() :
                    cog.env().flatMap(env ->
                            cog.evalBest(args.get(1)).map(stateAtom -> // Evaluate state argument
                                    cog.IS(env.actions(cog, stateAtom))) // Get actions as Is<List<Atom>>
                    ));
            // (AgentExecute $agent $action) -> (Act <perceptsIs> <rewardIs>) Expr Atom
            cog.IS("AgentExecute", args -> (args.size() != 2) ? empty() :
                    cog.env().flatMap(env ->
                            cog.evalBest(args.get(1)).map(actionAtom -> { // Evaluate action argument
                                var actResult = env.exe(cog, actionAtom);
                                // Return structured result: (Act Is<List<Atom>> Is<Double>)
                                return cog.E(cog.S("Act"), cog.IS(actResult.newPercepts()), cog.IS(actResult.reward()));
                            })
                    ));
        }
    }

    /**
     * Agent action result structure.
     */
    public record Act(List<Atom> newPercepts, double reward) {
    }

    /**
     * Example Game environment.
     */
    static class SimpleGame implements Game {
        private final Atom posA, posB, posGoal, moveAtoB, moveBtoGoal, moveOther, statePred, selfSym;
        private final Cog cog;
        private Atom currentStateSymbol;

        SimpleGame(Cog c) {
            this.cog = c;
            posA = c.S("Pos_A");
            posB = c.S("Pos_B");
            posGoal = c.S("AtGoal");
            moveAtoB = c.S("Move_A_B");
            moveBtoGoal = c.S("Move_B_Goal");
            moveOther = c.S("Move_Other");
            statePred = c.S("State");
            selfSym = c.S("Self");
            currentStateSymbol = posA; // Start at A
        }

        @Override
        public List<Atom> perceive(Cog cog) {
            return List.of(cog.E(statePred, selfSym, currentStateSymbol));
        }

        @Override
        public List<Atom> actions(Cog cog, Atom currentStateAtom) {
            // Simple check based on internal state symbol for demo
            if (currentStateSymbol.equals(posA)) return List.of(moveAtoB, moveOther);
            if (currentStateSymbol.equals(posB)) return List.of(moveBtoGoal, moveOther);
            return List.of(moveOther); // Only 'other' if at goal
        }

        @Override
        public Act exe(Cog cog, Atom actionSymbol) {
            var reward = -0.1; // Default cost per step
            if (currentStateSymbol.equals(posA) && actionSymbol.equals(moveAtoB)) {
                currentStateSymbol = posB;
                reward = 0.1;
                System.out.println("Env: Moved A -> B");
            } else if (currentStateSymbol.equals(posB) && actionSymbol.equals(moveBtoGoal)) {
                currentStateSymbol = posGoal;
                reward = 1.0;
                System.out.println("Env: Moved B -> Goal!");
            } else if (actionSymbol.equals(moveOther)) {
                reward = -0.2;
                System.out.println("Env: Executed 'Move_Other'");
            } else {
                reward = -0.5;
                System.out.println("Env: Invalid action: " + actionSymbol + " from " + currentStateSymbol);
            } // Penalty
            return new Act(perceive(cog), reward); // Return new percepts and reward
        }

        @Override
        public boolean isRunning() {
            return !currentStateSymbol.equals(posGoal);
        } // Stop when goal reached
    }

    /**
     * Handles bridging MeTTa evaluation to Java Reflection and MethodHandles.
     * Encapsulates the complexity of type conversion, method lookup, and invocation.
     */
    private static class Jvm {
        // Simple security: Allow list for classes/packages (can be loaded from MeTTa config)
        private static final Set<String> ALLOWED_CLASSES = Set.of(
                "java.lang.String", "java.lang.Math", "java.util.ArrayList", "java.util.HashMap",
                "java.lang.Double", "java.lang.Integer", "java.lang.Boolean", "java.lang.Object",
                "dumb.hyp5.Cog", "java.lang.Runnable" // Allow Runnable for proxy example
                // Add other safe classes as needed
        );
        private static final Set<String> ALLOWED_PACKAGES = Set.of("java.lang", "java.util");
        private final Cog cog;
        private final MethodHandles.Lookup lookup;

        public Jvm(Cog cog) {
            this.cog = cog;
            this.lookup = MethodHandles.lookup();

            cog.IS("JavaNew", this::javaNew);
            cog.IS("JavaCall", this::javaCall);
            cog.IS("JavaStaticCall", this::javaStaticCall);
            cog.IS("JavaField", this::javaField);
            cog.IS("JavaProxy", this::javaProxy);
        }

        private static boolean compatibleBox(Class<?> formal, Class<?> actual) {
            return (actual == double.class && formal == Double.class) ||
                    (actual == int.class && formal == Integer.class) ||
                    (actual == boolean.class && formal == Boolean.class) ||
                    (actual == long.class && formal == Long.class) ||
                    (actual == float.class && formal == Float.class) ||
                    (actual == char.class && formal == Character.class) ||
                    (actual == byte.class && formal == Byte.class) ||
                    (actual == short.class && formal == Short.class);
        }

        private static boolean compatibleUnbox(Class<?> formal, Class<?> actual) {
            return (formal == double.class && actual == Double.class) ||
                    (formal == int.class && actual == Integer.class) ||
                    (formal == boolean.class && actual == Boolean.class) ||
                    (formal == long.class && actual == Long.class) ||
                    (formal == float.class && actual == Float.class) ||
                    (formal == char.class && actual == Character.class) ||
                    (formal == byte.class && actual == Byte.class) ||
                    (formal == short.class && actual == Short.class);
        }

        // --- Type Conversion ---

        // --- Security Check ---
        private boolean isClassAllowed(String className) {
            if (ALLOWED_CLASSES.contains(className)) return true;
            // Check allowed packages (simple prefix check)
            return ALLOWED_PACKAGES.stream().anyMatch(pkg -> className.startsWith(pkg + "."));
            // TODO: Implement more robust security (e.g., load allow/deny lists from MeTTa)
        }

        private boolean isMemberAllowed(Class<?> clazz, String memberName) {
            // TODO: Add method/field level security checks if needed
            return isClassAllowed(clazz.getName()); // Basic check: if class is allowed, assume member is too (for now)
        }

        /**
         * Converts a MeTTa Atom to a suitable Java Object for reflection calls.
         */
        @Nullable
        private Object meTTaToJava(@Nullable Atom atom) {
            if (atom == null || atom.equals(SYMBOL_NIL)) return null;
            return switch (atom) {
                case Is<?> g -> g.value(); // Unwrap the value from Is<T>
                case Sym s when s.equals(SYMBOL_TRUE) -> Boolean.TRUE;
                case Sym s when s.equals(SYMBOL_FALSE) -> Boolean.FALSE;
                case Sym s -> s.name(); // Represent symbols as their names (potentially ambiguous)
                case Var v -> v.id(); // Represent vars by their full ID (e.g., "$x")
                case Expr e -> e; // Pass expressions directly (less common as arg, maybe for proxy handler)
            };
        }

        /**
         * Converts a Java Object result from reflection back to a MeTTa Atom.
         */
        private Atom javaToMeTTa(@Nullable Object obj) {
            if (obj == null) return SYMBOL_NIL;
            // Use IS to wrap Java objects, maintaining type info where possible
            if (obj instanceof Atom a) return a; // Avoid double wrapping if Java method returns Atom
            if (obj instanceof Boolean b) return b ? SYMBOL_TRUE : SYMBOL_FALSE; // Canonical True/False
            // Ensure numbers are Doubles for consistency in MeTTa space, Box primitives
            if (obj instanceof Byte b) return cog.IS(b.doubleValue());
            if (obj instanceof Short s) return cog.IS(s.doubleValue());
            if (obj instanceof Integer i) return cog.IS(i.doubleValue());
            if (obj instanceof Long l) return cog.IS(l.doubleValue());
            if (obj instanceof Float f) return cog.IS(f.doubleValue());
            // For collections or arrays, wrap as Is<List<Atom>> if possible
            if (obj instanceof List<?> l) return cog.IS(l.stream().map(this::javaToMeTTa).toList());
            if (obj instanceof Object[] arr) return cog.IS(Arrays.stream(arr).map(this::javaToMeTTa).toList());
            // Fallback: Wrap generic object in Is<Object>
            return cog.IS(/*"is:JavaObject:" + obj.getClass().getSimpleName(),*/ obj);
        }

        /**
         * Determines the Java Class type from a MeTTa Atom (for method matching).
         */
        private Class<?> getArgType(@Nullable Atom atom) {
            var obj = meTTaToJava(atom);
            if (obj == null) return Object.class; // Or Void.class? Need consistency. Use Object for null args.
            if (obj instanceof Double) return double.class; // Prefer primitive for matching
            if (obj instanceof Integer) return int.class;
            if (obj instanceof Long) return long.class;
            if (obj instanceof Float) return float.class;
            if (obj instanceof Boolean) return boolean.class;
            if (obj instanceof Character) return char.class;
            if (obj instanceof Byte) return byte.class;
            if (obj instanceof Short) return short.class;
            return obj.getClass();
        }

        @Nullable
        private Class<?> findClass(String className) {
            if (isClassAllowed(className)) {
                try {
                    return Class.forName(className);
                } catch (ClassNotFoundException e) {
                    System.err.println("JvmBridge Error: Class not found: " + className);
                }
            } else {
                System.err.println("JvmBridge Error: Class not allowed: " + className);
            }
            return null;
        }

        // (JavaNew <class_name_string> <arg1> <arg2> ...) -> Is<Object>
        public Optional<Atom> javaNew(List<Atom> args) {
            if (args.isEmpty()) return empty();
            var classNameAtom = args.getFirst();
            if (!(meTTaToJava(classNameAtom) instanceof String className))
                return Optional.of(cog.S("Error:ClassNameNotString"));

            var clazz = findClass(className);
            if (clazz == null) return Optional.of(cog.S("Error:ClassNotFound"));

            var constructorArgs = args.subList(1, args.size());
            var javaArgs = constructorArgs.stream().map(this::meTTaToJava).toArray();
            var argTypes = constructorArgs.stream().map(this::getArgType).toArray(Class<?>[]::new);

            try {
                // Use reflection API for constructor finding - simpler than MethodHandles here
                var constructor = clazz.getConstructor(argTypes);
                if (!isMemberAllowed(clazz, "<init>")) return Optional.of(cog.S("Error:ConstructorNotAllowed"));
                var instance = constructor.newInstance(javaArgs);
                return Optional.of(javaToMeTTa(instance));
            } catch (NoSuchMethodException e) { // Handle constructor not found or arg mismatch
                System.err.println("JvmBridge Error: Constructor not found for " + className + " with args " + Arrays.toString(argTypes) + " -> " + e);
                // TODO: Add more sophisticated constructor matching (e.g., handling subtypes, varargs)
                return Optional.of(cog.S("Error:ConstructorNotFound"));
            } catch (Exception e) { // InstantiationException, IllegalAccessException, InvocationTargetException
                System.err.println("JvmBridge Error: Constructor invocation failed for " + className + ": " + e);
                return Optional.of(cog.S("Error:ConstructorFailed"));
            }
        }

        // Common logic for instance and static calls using MethodHandles
        private Optional<Atom> invokeMethod(boolean isStatic, List<Atom> args) {
            if (args.size() < (isStatic ? 1 : 2))
                return Optional.of(cog.S("Error:InsufficientArgs")); // Static: class, method, [args...], Instance: instance, method, [args...]

            String identifier; // Class name for static, method name for instance
            Object target = null; // Null for static, instance object for instance call
            int methodArgStartIndex;

            if (isStatic) {
                if (!(meTTaToJava(args.get(0)) instanceof String className))
                    return Optional.of(cog.S("Error:ClassNameNotString"));
                identifier = className;
                if (!(meTTaToJava(args.get(1)) instanceof String methodName))
                    return Optional.of(cog.S("Error:MethodNameNotString"));
                methodArgStartIndex = 2;
                target = null; // Static call, no instance
            } else { // Instance call
                target = meTTaToJava(args.get(0));
                if (target == null) return Optional.of(cog.S("Error:TargetInstanceNull"));
                if (!(meTTaToJava(args.get(1)) instanceof String methodName))
                    return Optional.of(cog.S("Error:MethodNameNotString"));
                identifier = methodName;
                methodArgStartIndex = 2;
            }

            var methodName = isStatic ? (String) meTTaToJava(args.get(1)) : identifier;
            var clazz = isStatic ? findClass(identifier) : target.getClass();
            if (clazz == null) return Optional.of(cog.S("Error:ClassNotFound"));
            if (!isMemberAllowed(clazz, methodName)) return Optional.of(cog.S("Error:MethodNotAllowed"));

            var methodArgs = args.subList(methodArgStartIndex, args.size());
            var javaArgs = methodArgs.stream().map(this::meTTaToJava).toArray();
            var argTypes = methodArgs.stream().map(this::getArgType).toArray(Class<?>[]::new);

            try {
                MethodHandle methodHandle;
                // Find the appropriate method handle (static or virtual)
                var methodType = MethodType.methodType(Object.class, argTypes); // Use Object return type initially
                if (isStatic) {
                    // Need exact return type for lookup, try common ones or use reflection Method to get return type
                    // Simple approach: Find method via reflection first to get return type
                    var foundMethod = findMethod(clazz, methodName, argTypes);
                    if (foundMethod == null)
                        throw new NoSuchMethodException("Method " + methodName + " with args " + Arrays.toString(argTypes));
                    methodType = MethodType.methodType(foundMethod.getReturnType(), argTypes);
                    methodHandle = lookup.findStatic(clazz, methodName, methodType);
                } else {
                    var foundMethod = findMethod(clazz, methodName, argTypes);
                    if (foundMethod == null)
                        throw new NoSuchMethodException("Method " + methodName + " with args " + Arrays.toString(argTypes));
                    methodType = MethodType.methodType(foundMethod.getReturnType(), argTypes);
                    methodHandle = lookup.findVirtual(clazz, methodName, methodType);
                }

                // Invoke the method handle
                Object result;
                if (isStatic) {
                    result = methodHandle.invokeWithArguments(javaArgs);
                } else {
                    // Prepend target instance to arguments for invokeWithArguments on virtual handle
                    var invokeArgs = new Object[javaArgs.length + 1];
                    invokeArgs[0] = target;
                    System.arraycopy(javaArgs, 0, invokeArgs, 1, javaArgs.length);
                    result = methodHandle.invokeWithArguments(invokeArgs);
                }

                return Optional.of(javaToMeTTa(result));
            } catch (NoSuchMethodException e) {
                // Check for static field access request using method call syntax (e.g., Math.PI)
                if (isStatic && methodArgs.isEmpty()) {
                    try {
                        var field = clazz.getField(methodName);
                        if (!isMemberAllowed(clazz, field.getName()))
                            return Optional.of(cog.S("Error:FieldNotAllowed"));
                        if (java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                            return Optional.of(javaToMeTTa(field.get(null)));

                    } catch (NoSuchFieldException | IllegalAccessException ignored) {
                    } // Fall through if not a static field
                }
                System.err.println("JvmBridge Error: Method not found: " + clazz.getName() + "." + methodName + " with args " + Arrays.toString(argTypes) + " -> " + e);
                return Optional.of(cog.S("Error:MethodNotFound"));
            } catch (Throwable e) { // Catch Throwable for MethodHandle invoke exact errors
                System.err.println("JvmBridge Error: Method invocation failed for " + clazz.getName() + "." + methodName + ": " + e);
                // Unwrap InvocationTargetException if possible
                if (e instanceof InvocationTargetException ite && ite.getCause() != null)
                    ite.getCause().printStackTrace();
                return Optional.of(cog.S("Error:MethodInvocationFailed"));
            }
        }

        // Helper to find method reflectively, handling primitives/wrappers (simplistic)
        @Nullable
        private Method findMethod(Class<?> clazz, String methodName, Class<?>[] argTypes) {
            try {
                return clazz.getMethod(methodName, argTypes);
            } catch (NoSuchMethodException e) {
                // Basic retry logic: try wrapper types for primitives, or vice versa (can be expanded)
                for (var method : clazz.getMethods()) {
                    if (method.getName().equals(methodName) && method.getParameterCount() == argTypes.length) {
                        // Simple check: assignable types (could be more robust)
                        var match = true;
                        var paramTypes = method.getParameterTypes();
                        for (var i = 0; i < argTypes.length; i++) {
                            if (!isTypeCompatible(paramTypes[i], argTypes[i])) {
                                match = false;
                                break;
                            }
                        }
                        if (match) return method;
                    }
                }
                return null; // Not found after simple retry
            }
        }

        // Basic type compatibility check (primitive/wrapper awareness)
        private boolean isTypeCompatible(Class<?> formalParamType, Class<?> actualArgType) {
            if (formalParamType.isAssignableFrom(actualArgType)) return true;
            // Check primitive/wrapper boxing/unboxing
            if (formalParamType.isPrimitive()) {
                return compatibleUnbox(formalParamType, actualArgType);
            } else if (actualArgType.isPrimitive()) {
                return compatibleBox(formalParamType, actualArgType);
            } else
                return false; // Default to false if not assignable or standard primitive/wrapper pair
        }

        // (JavaCall <instance_is> <method_name_string> <arg1> <arg2> ...) -> Is<Result> or Nil
        public Optional<Atom> javaCall(List<Atom> args) {
            return invokeMethod(false, args);
        }

        // (JavaStaticCall <class_name_string> <method_name_string> <arg1> <arg2> ...) -> Is<Result> or Nil
        public Optional<Atom> javaStaticCall(List<Atom> args) {
            return invokeMethod(true, args);
        }

        // (JavaField <instance_is | class_name_string> <field_name_string>) -> Is<Value> (Get field)
        // (JavaField <instance_is | class_name_string> <field_name_string> <value_to_set>) -> True/False (Set field)
        public Optional<Atom> javaField(List<Atom> args) {
            if (args.size() < 2 || args.size() > 3) return Optional.of(cog.S("Error:InvalidFieldArgs"));

            var targetOrClass = meTTaToJava(args.get(0));
            if (!(meTTaToJava(args.get(1)) instanceof String fieldName))
                return Optional.of(cog.S("Error:FieldNameNotString"));

            Class<?> clazz;
            Object instance = null;
            var isStatic = false;
            if (targetOrClass instanceof String className) {
                clazz = findClass(className);
                isStatic = true;
            } else if (targetOrClass != null) {
                clazz = targetOrClass.getClass();
                instance = targetOrClass;
            } else {
                return Optional.of(cog.S("Error:InvalidFieldTarget"));
            }

            if (clazz == null) return Optional.of(cog.S("Error:ClassNotFound"));
            if (!isMemberAllowed(clazz, fieldName)) return Optional.of(cog.S("Error:FieldNotAllowed"));

            try {
                var field = clazz.getField(fieldName); // Get public field only
                // Check static modifier consistency
                if (isStatic != java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                    return Optional.of(cog.S("Error:StaticMismatch"));

                if (args.size() == 2) { // Get field value
                    var value = field.get(instance); // 'instance' is null for static fields
                    return Optional.of(javaToMeTTa(value));
                } else { // Set field value
                    var valueToSet = meTTaToJava(args.get(2));
                    // Basic type check (can be improved)
                    Class<?> fieldType = field.getType();
                    if (!isTypeCompatible(fieldType, getArgType(args.get(2)))) {
                        // Attempt conversion if possible (e.g., Double to int) - needs more robust conversion logic
                        if (fieldType == int.class && valueToSet instanceof Double d) valueToSet = d.intValue();
                        else if (fieldType == long.class && valueToSet instanceof Double d) valueToSet = d.longValue();
                            // ... add other conversions as needed
                        else return Optional.of(cog.S("Error:FieldTypeMismatch"));
                    }
                    field.set(instance, valueToSet); // 'instance' is null for static fields
                    return Optional.of(SYMBOL_TRUE); // Success
                }
            } catch (NoSuchFieldException e) {
                System.err.println("JvmBridge Error: Field not found: " + clazz.getName() + "." + fieldName + " -> " + e);
                return Optional.of(cog.S("Error:FieldNotFound"));
            } catch (IllegalAccessException e) {
                System.err.println("JvmBridge Error: Field access denied: " + clazz.getName() + "." + fieldName + " -> " + e);
                return Optional.of(cog.S("Error:FieldAccessDenied"));
            } catch (Exception e) {
                System.err.println("JvmBridge Error: Field operation failed: " + clazz.getName() + "." + fieldName + " -> " + e);
                return Optional.of(cog.S("Error:FieldOperationFailed"));
            }
        }

        // (JavaProxy <interface_name_string> <handler_metta_expr>) -> Is<Proxy>
        public Optional<Atom> javaProxy(List<Atom> args) {
            if (args.size() != 2) return Optional.of(cog.S("Error:InvalidProxyArgs"));
            if (!(meTTaToJava(args.get(0)) instanceof String interfaceName))
                return Optional.of(cog.S("Error:InterfaceNameNotString"));
            var handlerExprTemplate = args.get(1); // The MeTTa expression to handle calls

            var iface = findClass(interfaceName);
            if (iface == null || !iface.isInterface()) return Optional.of(cog.S("Error:InvalidInterface"));
            // Basic security check on interface
            if (!isClassAllowed(interfaceName)) return Optional.of(cog.S("Error:InterfaceNotAllowed"));

            try {
                // Create the proxy instance
                // Wrap the proxy instance in an Is atom
                return Optional.of(javaToMeTTa(Proxy.newProxyInstance(
                        this.getClass().getClassLoader(), // Cog's classloader
                        new Class<?>[]{iface},
                        handler(handlerExprTemplate)
                )));
            } catch (IllegalArgumentException e) { // From Proxy.newProxyInstance
                System.err.println("JvmBridge Error: Proxy creation failed for interface " + interfaceName + ": " + e);
                return Optional.of(cog.S("Error:ProxyCreationFailed"));
            } catch (Exception e) { // Catch potential errors during handler setup (less likely)
                System.err.println("JvmBridge Error: Unexpected error during proxy setup: " + e);
                return Optional.of(cog.S("Error:ProxySetupFailed"));
            }
        }

        /**
         * Create an InvocationHandler that evaluates the MeTTa handler expression
         */
        private InvocationHandler handler(Atom handlerExprTemplate) {
            return (proxy, method, methodArgs) -> {
                // 1. Convert Java method call arguments to MeTTa atoms
                var proxyAtom = javaToMeTTa(proxy); // Represent proxy instance if needed
                var methodNameAtom = cog.S(method.getName());
                // Wrap args potentially, handle nulls
                List<Atom> argAtoms = (methodArgs == null) ? List.of() : Arrays.stream(methodArgs).map(this::javaToMeTTa).toList();
                Atom argsListAtom = cog.IS(argAtoms); // Represent args as Is<List<Atom>>

                // 2. Construct the MeTTa expression to evaluate using the handler template
                //    Substitute $proxy, $method, $args variables in the template
                var exprToEval = cog.unify.subst(handlerExprTemplate, new Bind(Map.of(
                        cog.V("proxy"), proxyAtom,
                        cog.V("method"), methodNameAtom,
                        cog.V("args"), argsListAtom // Bind the Is<List> atom
                )));

                // 3. Evaluate the expression using Cog's interpreter
                // 4. Convert the MeTTa result back to the expected Java type
                var javaResult = cog.evalBest(exprToEval).map(this::meTTaToJava).orElse(null);

                // 5. Handle return type compatibility (primitive void, etc.)
                var returnType = method.getReturnType();
                if (returnType == void.class || returnType == Void.class)
                    return null;
                if (javaResult == null && returnType.isPrimitive())
                    throw new MettaProxyException("MeTTa handler returned null/Nil for primitive return type " + returnType.getName());

                // Basic type casting/conversion (can be improved)
                if (javaResult != null && !isTypeCompatible(returnType, javaResult.getClass())) {
                    // Attempt conversion (e.g., Double to int/float/etc.)
                    return switch (javaResult) {
                        case Double d when returnType == int.class -> d.intValue();
                        case Double d when returnType == long.class -> d.longValue();
                        case Double d when returnType == float.class -> d.floatValue();
                        case Boolean b when returnType == boolean.class -> b; // Already handled?
                        default ->
                                throw new MettaProxyException("MeTTa handler result type (" + javaResult.getClass().getName() + ") incompatible with method return type (" + returnType.getName() + ")");
                    };
                    // Add more conversions or throw error
                }
                return javaResult;
            };
        }

        // Custom exception for proxy handler issues
        static class MettaProxyException extends RuntimeException {
            MettaProxyException(String message) {
                super(message);
            }
        }
    }

    /**
     * Interprets MeTTa expressions by applying rewrite rules and executing Is functions.
     * The core evaluation strategy (`evalRecursive`) is a candidate for MeTTa-driven meta-interpretation.
     */
    public class Interp {
        private final Cog cog;

        public Interp(Cog cog) {
            this.cog = cog;
        }

        public Atom subst(Atom atom, Bind bind) {
            return cog.unify.subst(atom, bind);
        }

        /**
         * Evaluates an atom using the default max depth.
         */
        public List<Atom> eval(Atom atom, int maxDepth) {
            var results = evalRecursive(atom, maxDepth, new HashSet<>());
            // Return original atom if evaluation yields no results or only cycles
            return results.isEmpty() ? List.of(atom) : results.stream().distinct().limit(INTERPRETER_MAX_RESULTS).toList();
        }

        /**
         * Recursive evaluation core logic with depth limiting and cycle detection.
         * # MeTTa Integration Pathway: Meta-Interpretation (See Class JavaDoc and v5.0 comments)
         * Current Java strategy: Specific rule -> General rule -> Is function -> Evaluate children -> Self.
         * This could be replaced by querying MeTTa meta-rules defining the evaluation process.
         */
        private List<Atom> evalRecursive(Atom atom, int depth, Set<String> visitedInPath) {
            if (depth <= 0) return List.of(atom); // Max depth reached
            var atomId = atom.id();
            if (!visitedInPath.add(atomId)) return List.of(atom); // Cycle detected

            List<Atom> combinedResults = new ArrayList<>();
            try {
                switch (atom) {
                    // --- Base Cases: Non-evaluatable atoms ---
                    case Sym s -> combinedResults.add(s);
                    case Var v ->
                            combinedResults.add(v); // Variables typically don't evaluate further unless substituted
                    case Is<?> ga when !ga.isFn() -> combinedResults.add(ga); // Non-function Is atoms are values

                    // --- Evaluate Expressions ---
                    case Expr expr -> {
                        // Strategy 1: Specific equality rule `(= <expr> $result)`
                        var resultVar = V("evalRes" + depth); // Unique var name per depth
                        Atom specificQuery = E(SYMBOL_EQ, expr, resultVar);
                        var specificMatches = cog.mem.query(specificQuery);
                        if (!specificMatches.isEmpty()) {
                            for (var match : specificMatches) {
                                match.bind.get(resultVar).ifPresent(target ->
                                        combinedResults.addAll(evalRecursive(target, depth - 1, new HashSet<>(visitedInPath)))); // Recurse on result
                                if (combinedResults.size() >= INTERPRETER_MAX_RESULTS) break;
                            }
                            // If specific matches found, potentially stop here (controlled by meta-rules in future)
                            // Current: Continue non-deterministically
                        }

                        // Strategy 2: General equality rule `(= <pattern> <template>)` (only if specific rules didn't yield enough results)
                        if (combinedResults.size() < INTERPRETER_MAX_RESULTS) {
                            var pVar = V("p" + depth);
                            var tVar = V("t" + depth);
                            Atom generalQuery = E(SYMBOL_EQ, pVar, tVar);
                            var ruleMatches = cog.mem.query(generalQuery);
                            for (var ruleMatch : ruleMatches) {
                                var pattern = ruleMatch.bind.get(pVar).orElse(null);
                                var template = ruleMatch.bind.get(tVar).orElse(null);
                                // Ensure valid rule and pattern is not the expression itself (handled by specific match)
                                if (pattern == null || template == null || pattern.equals(expr)) continue;
                                // Try to unify the expression with the rule's pattern
                                cog.unify.unify(pattern, expr, Bind.EMPTY).ifPresent(exprBind -> {
                                    var result = subst(template, exprBind); // Apply bindings to template
                                    combinedResults.addAll(evalRecursive(result, depth - 1, new HashSet<>(visitedInPath))); // Recurse on result
                                });
                                if (combinedResults.size() >= INTERPRETER_MAX_RESULTS) break;
                            }
                        }

                        // Strategy 3: Is Function execution (Applicative Order) (only if rules didn't yield enough results)
                        if (combinedResults.size() < INTERPRETER_MAX_RESULTS && expr.head() instanceof Is<?> ga && ga.isFn()) {
                            List<Atom> evaluatedArgs = new ArrayList<>();
                            var argEvalOk = true;
                            // Evaluate arguments first (applicative order)
                            for (var arg : expr.tail()) {
                                var argResults = evalRecursive(arg, depth - 1, new HashSet<>(visitedInPath));
                                // Strict: Requires single result for each arg. Meta-rules could change this.
                                if (argResults.size() != 1) {
                                    argEvalOk = false;
                                    break;
                                }
                                evaluatedArgs.add(argResults.getFirst());
                            }
                            if (argEvalOk) { // If all args evaluated successfully to single results
                                ga.apply(evaluatedArgs).ifPresent(execResult -> // Execute the Java function
                                        combinedResults.addAll(evalRecursive(execResult, depth - 1, new HashSet<>(visitedInPath)))); // Evaluate the function's result
                            }
                        }

                        // Strategy 4: Evaluate children and reconstruct (if no rules/exec applied or yielded results)
                        // This acts as the fallback for structured data / constructors.
                        if (combinedResults.isEmpty()) {
                            var childrenChanged = false;
                            List<Atom> evaluatedChildren = new ArrayList<>();
                            // Evaluate Head (if exists)
                            if (expr.head() != null) {
                                var headResults = evalRecursive(expr.head(), depth - 1, new HashSet<>(visitedInPath));
                                // Use single result, else keep original head
                                var newHead = (headResults.size() == 1) ? headResults.getFirst() : expr.head();
                                evaluatedChildren.add(newHead);
                                if (!newHead.equals(expr.head())) childrenChanged = true;
                            }
                            // Evaluate Tail
                            for (var child : expr.tail()) {
                                var childResults = evalRecursive(child, depth - 1, new HashSet<>(visitedInPath));
                                // Use single result, else keep original child
                                var newChild = (childResults.size() == 1) ? childResults.getFirst() : child;
                                evaluatedChildren.add(newChild);
                                if (!newChild.equals(child)) childrenChanged = true;
                            }
                            // Return the reconstructed expression if changed, otherwise the original.
                            combinedResults.add(childrenChanged ? cog.E(evaluatedChildren) : expr);
                        }
                    }

                    // --- Evaluate Is functions called directly (less common) ---
                    // If an Is function is evaluated directly (not as head of expr), it evaluates to itself.
                    case Is<?> ga -> combinedResults.add(ga);
                }
            } finally {
                visitedInPath.remove(atomId); // Backtrack from current path visit
            }
            return combinedResults;
        }
    }

    /**
     * Agent whose core behavior (perceive-select-execute-learn cycle) is driven by evaluating MeTTa expressions.
     */
    public class Agent {
        private final Cog cog;
        private final Atom AGENT_STEP_EXPR; // Expression evaluated each cycle, e.g., (AgentStep Self)
        private final Atom SELF_SYM; // Agent's identifier

        public Agent(Cog cog) {
            this.cog = cog;
            this.SELF_SYM = cog.S("Self");
            this.AGENT_STEP_EXPR = cog.E(cog.S("AgentStep"), SELF_SYM);
            // Ensure agent control symbols are known and protected/boosted if needed
            Stream.of("AgentStep", "AgentPerceive", "AgentSelectAction", "AgentExecute", "AgentLearn", "CheckGoal", "UpdateUtility", "Act", "Utility")
                    .forEach(name -> {
                        var s = cog.S(name);
                        cog.mem.updateTruth(s, Truth.TRUE);
                    });
        }

        /**
         * Runs the agent loop. The loop structure is Java, but each step's logic
         * is determined by evaluating the AGENT_STEP_EXPR in MeTTa.
         * # MeTTa Integration Pathway: Agent Control Flow (See Class JavaDoc and v5.0 comments)
         * The Java loop could be replaced by a recursive MeTTa evaluation.
         */
        public void run(Game env, Atom goalPattern, int maxCycles) {
            System.out.println("\n--- Agent Run Start (MeTTa Driven Steps) ---");
            initializeGoal(goalPattern); // Represent goal in MeTTa space

            for (var cycle = 0; cycle < maxCycles && env.isRunning(); cycle++) {
                var time = cog.tick();
                System.out.printf("\n--- Agent Cycle %d (Time: %d) ---%n", cycle + 1, time);

                // Evaluate the main agent step expression (defined in MeTTa).
                // This triggers MeTTa rules for perceive, select, execute, learn.
                System.out.println("Agent: Evaluating step: " + AGENT_STEP_EXPR);
                var stepResults = cog.eval(AGENT_STEP_EXPR); // <<< CORE AGENT LOGIC EXECUTION >>>

                // Optional: Log step result (depends on what AgentStep returns)
                if (!stepResults.isEmpty() && !stepResults.getFirst().equals(AGENT_STEP_EXPR)) {
                    System.out.println("Agent: Step result -> " + stepResults.stream().map(Atom::toString).collect(Collectors.joining(", ")));
                } else if (stepResults.isEmpty()) {
                    System.out.println("Agent: Step evaluation yielded no result (check rules/state).");
                } else {
                    // If AgentStep evaluates to itself, it might mean no action was applicable or rules are stuck
                    System.out.println("Agent: Step evaluation resulted in self (no change/action determined by rules).");
                }

                // Check if goal is achieved by evaluating a goal-checking expression in MeTTa
                Atom goalCheckExpr = cog.E(cog.S("CheckGoal"), SELF_SYM, goalPattern);
                if (cog.evalBest(goalCheckExpr).filter(SYMBOL_TRUE::equals).isPresent()) {
                    System.out.println("*** Agent: Goal Achieved (according to MeTTa CheckGoal evaluation)! ***");
                    break; // Exit loop
                }
                // Optional delay between cycles
                // try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }

            // Final status after loop
            var goalMet = cog.evalBest(cog.E(cog.S("CheckGoal"), SELF_SYM, goalPattern)).filter(SYMBOL_TRUE::equals).isPresent();
            if (!env.isRunning() && !goalMet)
                System.out.println("--- Agent Run Finished (Environment Stopped, Goal Not Met) ---");
            else if (!goalMet) System.out.println("--- Agent Run Finished (Max Cycles Reached, Goal Not Met) ---");
            else System.out.println("--- Agent Run Finished (Goal Met) ---");
        }

        /**
         * Adds the goal representation to memory with high priority.
         */
        private void initializeGoal(Atom goalPattern) {
            // Represent goal as: (Goal Self <pattern>)
            Atom goalAtom = cog.add(cog.E(cog.S("Goal"), SELF_SYM, goalPattern));
            cog.mem.boost(goalAtom, PRI_BOOST_ON_GOAL_FOCUS); // Boost the goal fact
            cog.mem.boost(goalPattern, PRI_BOOST_ON_GOAL_FOCUS * 0.8); // Boost the pattern too
            cog.mem.updateTruth(goalAtom, Truth.TRUE); // Mark goal as true fact initially
            System.out.println("Agent: Goal initialized in MeTTa space -> " + goalAtom + " with value " + cog.mem.value(goalAtom));
        }
    }
}
