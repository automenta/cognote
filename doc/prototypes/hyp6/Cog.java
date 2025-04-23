package dumb.hyp6;

import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
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
 * Cog - Cognitive Logic Engine
 * <p>
 * A unified, self-aware (via homoiconicity & reflection), and recursively self-improving
 * cognitive architecture implementing a Hyperon/MeTTa-like symbolic system.
 * It integrates parsing, pattern matching, rule-based rewriting, probabilistic reasoning,
 * attention mechanisms, agent interaction, and **JVM reflection/MethodHandles** within
 * a single, consolidated Java class structure, striving for theoretical elegance and practical utility.
 * <p>
 * ## Core Design Pillars:
 * - **Homoiconicity:** Code (MeTTa rules/expressions) *is* data (Atoms), enabling reflection and self-modification.
 * - **Unified Representation:** All concepts, relations, rules, procedures, types, states, goals are {@link Atom}s.
 * - **Memory (`Mem`):** Central knowledge store holding immutable Atoms, indexed for efficient querying.
 * - **Metadata (`Value`):** Associates Atoms with dynamically changing {@link Truth}, {@link Pri}, {@link Time} via atomic updates.
 * - **MeTTa Syntax & Parsing (`MettaParser`):** LISP-like textual representation for Atoms.
 * - **MeTTa Execution (`Interp`):** Evaluates expressions via rule-based rewriting (using {@link Unify}) and execution of primitive operations (`Is` functions).
 * - **Bridged Primitives (`Is`):** Connect MeTTa symbols to Java code/data for I/O, math, game interaction, and JVM control via {@link Jvm}.
 * - **Probabilistic & Priority Driven:** Truth values handle uncertainty; Pri values (STI/LTI) guide attention and forgetting.
 * - **Agent Framework (`Agent`):** Demonstrates perception, reasoning (via MeTTa evaluation), action, and learning, designed to be driven by MeTTa rules.
 * - **JVM Integration (`Jvm`):** Allows MeTTa code to introspect and interact with Java (object creation, method calls, field access, proxies).
 * - **Modern Java:** Leverages Records, Sealed Interfaces, Streams, Concurrent Collections, AtomicReference, MethodHandles for conciseness and robustness.
 * <p>
 * ## Self-Improvement & MeTTa Control Pathways:
 * The system's potential for self-improvement stems from its homoiconicity combined with the JVM bridge.
 * MeTTa rules can be added/modified via `add-atom`, changing the system's reasoning.
 * Furthermore, key components are designed or marked as candidates for MeTTa-driven control:
 * <p>
 * 1.  **Interpreter Strategy (`Interp.evalRecursive`):** *Candidate:* Evaluation steps could be defined by MeTTa meta-rules queried by the interpreter, allowing dynamic strategy changes. (See comments in {@link Interp#evalRecursive}).
 * 2.  **JVM Integration (`Core.initIsFn`, `Jvm`):** ***Implemented:*** `Java*` Is functions allow MeTTa scripts to directly interact with and control the JVM.
 * 3.  **Agent Control Flow (`Agent.run`):** ***Partially Implemented:*** The core agent logic (perceive-select-act-learn) is driven by evaluating a MeTTa expression (`AgentStep`), allowing MeTTa rules to define the agent's behavior. The outer loop remains Java for efficiency. (See comments in {@link Agent#run}).
 * 4.  **Configuration Management:** *Candidate:* Non-performance-critical parameters could be loaded from MeTTa atoms, enabling dynamic reconfiguration. (See comments near constants).
 *
 * @version 6.1 - Refined Structure, Integrated Agent Rules, Enhanced Documentation
 */
public final class Cog {

    // --- Configuration Constants (Potential MeTTa Candidates) ---
    // These constants define fixed operational parameters. While budget/Pri parameters
    // are explicitly excluded from MeTTa storage due to performance/dependency concerns,
    // others *could* potentially be loaded from MeTTa atoms like `(: truth-default-sensitivity 1.0)`
    // or `(: interpreter-default-max-depth 15)` during initialization or runtime via dedicated Is functions.
    private static final double TRUTH_DEFAULT_SENSITIVITY = 1.0;
    private static final double TRUTH_MIN_CONFIDENCE_MATCH = 0.01; // Candidate for MeTTa config
    private static final double TRUTH_REVISION_CONFIDENCE_THRESHOLD = 0.1; // Candidate for MeTTa config
    private static final float PRI_INITIAL_STI = 0.2f; // Excluded (Budget)
    private static final float PRI_INITIAL_LTI_FACTOR = 0.1f; // Excluded (Budget)
    private static final float PRI_STI_DECAY_RATE = 0.08f; // Excluded (Budget)
    private static final float PRI_LTI_DECAY_RATE = 0.008f; // Excluded (Budget)
    private static final float PRI_STI_TO_LTI_RATE = 0.02f; // Excluded (Budget)
    private static final float PRI_ACCESS_BOOST = 0.08f; // Excluded (Budget)
    private static final float PRI_BOOST_ON_REVISION_MAX = 0.5f; // Excluded (Budget)
    private static final float PRI_BOOST_ON_GOAL_FOCUS = 0.95f; // Excluded (Budget)
    private static final float PRI_BOOST_ON_PERCEPTION = 0.75f; // Excluded (Budget)
    private static final float PRI_MIN_FORGET_THRESHOLD = 0.015f; // Candidate for MeTTa config (tune forgetting aggression)
    private static final long FORGET_PERIOD_MS = 12000; // Candidate for MeTTa config
    private static final int MEM_CAPACITY_DEFAULT = 18000; // Candidate for MeTTa config
    private static final int FORGETTING_TARGET_MEM_SIZE_FACTOR = 80; // Candidate for MeTTa config (% of trigger size to retain)
    private static final int INTERPRETER_DEFAULT_MAX_DEPTH = 15; // Candidate for MeTTa config
    private static final int INTERPRETER_MAX_RESULTS = 50; // Candidate for MeTTa config
    private static final double AGENT_UTILITY_THRESHOLD = 0.1; // Candidate for MeTTa config (Agent behaviour related)
    private static final double AGENT_RANDOM_ACTION_PROBABILITY = 0.05; // Candidate for MeTTa config (Agent behaviour related)

    private static final String VAR_PREFIX = "$";
    // Symbols protected from forgetting (core syntax, types, essential Is functions, agent concepts)
    private static final Set<String> PROTECTED_SYMBOLS = Set.of(
            // Core syntax and types
            "=", ":", "->", "Type", "True", "False", "Nil", "Number", "Bool", "String", "Atom", "List", "JavaObject", "Fn",
            // Agent-related concepts
            "Self", "Goal", "State", "Action", "Utility", "Implies", "Seq", "Act",
            // Core Is operations (implementations are Java, symbols are entry points)
            "match", "eval", "add-atom", "remove-atom", "get-value", "&self",
            // Control
            "If", "Let", "IsEmpty", "IsNil",
            // Arithmetic
            "_+", "_-", "_*", "_/", "+", "-", "*", "/", "RandomFloat",
            // Comparison
            "_==", "_>", "_<", "==", ">", "<",
            // List / String
            "Concat", "RandomChoice", "FirstNumOrZero", // Added from demo/agent rules
            // Agent operations
            "AgentStep", "AgentPerceive", "AgentAvailableActions", "AgentExecute", "AgentLearn", "GetEnv", "CheckGoal", "UpdateUtility",
            // JVM Integration
            "JavaNew", "JavaCall", "JavaStaticCall", "JavaField", "JavaProxy"
    );

    // --- Core Symbols (Canonical instances initialized in Core) ---
    public static Atom SYM_EQ, SYM_COLON, SYM_ARROW, SYM_TYPE, SYM_TRUE, SYM_FALSE, SYM_SELF, SYM_NIL;

    public final Mem mem;
    public final Unify unify;
    private final Interp interp;
    private final MettaParser parser;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong logicalTime = new AtomicLong(0);

    private final Jvm jvm; // JVM Bridge

    /**
     * support multiple games
     */
    @Deprecated
    private @Nullable Game game; // Holds the current game for agent Is functions

    public Cog() {
        this.mem = new Mem(this::getLogicalTime, MEM_CAPACITY_DEFAULT);
        this.unify = new Unify(this.mem);
        this.interp = new Interp(this);
        this.parser = new MettaParser(this);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "Cog-Maintenance");
            t.setDaemon(true); // Allow JVM exit
            return t;
        });
        // Schedule periodic memory maintenance (decay, forgetting)
        scheduler.scheduleAtFixedRate(this::maintenance, FORGET_PERIOD_MS, FORGET_PERIOD_MS, TimeUnit.MILLISECONDS);

        this.jvm = new Jvm(this); // Initialize the JVM bridge before Core uses its functions
        new Core(this); // Initializes symbols, Is functions, core rules, loads bootstrap MeTTa

        System.out.println("Cog (v6.1 Refined) Initialized. Init Size: " + mem.size());
    }

    /**
     * clamp a double value between 0.0 and 1.0.
     */
    public static double unitize(double v) {
        return Math.max(0, Math.min(1, v));
    }

    public static float unitize(float v) {
        return Math.max(0, Math.min(1f, v));
    }

    // --- Main Demonstration (Illustrates current capabilities including JVM reflection and Agent) ---
    public static void main(String[] args) {
        var cog = new Cog();
        try {
            System.out.println("\n--- Cog Synthesized Test Suite (v6.1) ---");

            // --- [1] Parsing & Atom Basics ---
            printSectionHeader(1, "Parsing & Atom Basics");
            var red = cog.parse("Red");
            cog.add(red);
            cog.mem.updateTruth(red, new Truth(0.9, 5.0));
            System.out.println("Parsed Red: " + red + " | Value: " + cog.mem.value(red));

            // --- [2] Unification ---
            printSectionHeader(2, "Unification");
            var fact1 = cog.parse("(Knows Self Something)");
            var pattern1 = cog.parse("(Knows Self $w)");
            cog.add(fact1);
            testUnification(cog, "Simple Match", pattern1, fact1);

            // --- [3] MeTTa Evaluation - Core Rules ---
            printSectionHeader(3, "MeTTa Evaluation - Core Rules");
            evaluateAndPrint(cog, "Peano Add 2+1", cog.parse("(add (S (S Z)) (S Z))")); // Expect (S (S (S Z)))
            evaluateAndPrint(cog, "Arithmetic (2+3)*4", cog.parse("(* (+ 2.0 3.0) 4.0)")); // Expect is:Double:20.0
            evaluateAndPrint(cog, "If (5 > 3)", cog.parse("(If (> 5.0 3.0) FiveIsGreater ThreeIsGreater)")); // Expect FiveIsGreater

            // --- [4] Pattern Matching Query ---
            printSectionHeader(4, "Pattern Matching Query");
            cog.load("(Knows Self Something)\n(Knows Dean Something)");
            evaluateAndPrint(cog, "Match Query", cog.parse("(match &self (Knows $p Something) $p)")); // Expect is:List<Atom>:[Self, Dean]

            // --- [5] Metaprogramming ---
            printSectionHeader(5, "Metaprogramming");
            var ruleToAddAtom = cog.parse("(= (NewPred ConceptX) ResultX)");
            evaluateAndPrint(cog, "Add Rule Meta", cog.E(cog.S("add-atom"), ruleToAddAtom)); // Adds the rule
            evaluateAndPrint(cog, "Test New Rule", cog.parse("(NewPred ConceptX)")); // Expect [ResultX]

            // --- [6] Agent Simulation (Driven by MeTTa `AgentStep` evaluation) ---
            printSectionHeader(6, "Agent Simulation (MeTTa Driven)");
            // Agent rules are now loaded by Core, no need to load them here.
            var env = new SimpleGame(cog);
            var agentGoal = cog.parse("(State Self AtGoal)");
            cog.runAgent(env, agentGoal, 10); // Run for max 10 cycles or until goal

            System.out.println("\nQuerying agent utility after MeTTa-driven run:");
            var utilQuery = cog.parse("(= (Utility Self) $val)");
            var utilResults = cog.query(utilQuery);
            printQueryResults(utilResults);

            // --- [7] JVM Integration Examples ---
            printSectionHeader(7, "JVM Integration (Reflection/MethodHandles)");
            evaluateAndPrint(cog, "Java New String", cog.parse("(JavaNew \"java.lang.String\" \"Hello from MeTTa\")"));
            evaluateAndPrint(cog, "Java Static Math.max", cog.parse("(JavaStaticCall \"java.lang.Math\" \"max\" 5.0 10.0)"));
            evaluateAndPrint(cog, "Java Instance Call",
                    cog.parse("(Let (= $list (JavaNew \"java.util.ArrayList\")) " +
                            "     (JavaCall $list \"add\" \"item1\") " +
                            "     (JavaCall $list \"add\" \"item2\") " +
                            "     (JavaCall $list \"size\"))")); // Expect Is<Double:2.0>
            evaluateAndPrint(cog, "Java Static Field Get (Math.PI)", cog.parse("(JavaStaticCall \"java.lang.Math\" \"PI\")"));
            // TestClass for field access needs to be defined *outside* Cog or be static inner
            cog.load("""
                        (= (TestJavaField $cn $fn $fv)
                           (Let (= $obj (JavaNew $cn))
                             (Let $_ (JavaField $obj $fn $fv) ; Set field
                               (JavaField $obj $fn)))) ; Get field
                    """);
            // Requires a public TestClass like: public static class TestClass { public String myField = "Initial"; }
            // evaluateAndPrint(cog, "Java Instance Field Get/Set", cog.parse("(TestJavaField \"dumb.hyp6.Cog$TestClass\" \"myField\" \"NewValue\")")); // Assuming TestClass exists
            System.out.println("Note: Field access examples require a public (static inner) TestClass with accessible fields.");

            cog.load("""
                    ; Define handler logic for Runnable proxy using MeTTa rules
                    (= (MyRunnableHandler $proxy $methodName $args)
                       (Concat "Runnable proxy executed method: " $methodName " via MeTTa handler"))
                    """);
            evaluateAndPrint(cog, "Java Proxy Runnable",
                    cog.parse("(Let (= $handler (MyRunnableHandler $proxy $method $args)) " + // Define handler template atom
                            "     (Let (= $proxy (JavaProxy \"java.lang.Runnable\" $handler)) " + // Create proxy Is<Proxy>
                            "          (JavaCall $proxy \"run\")))")); // Call run() on proxy -> evaluates handler -> returns Is<String>

            // --- [8] Forgetting & Maintenance ---
            printSectionHeader(8, "Forgetting & Maintenance");
            System.out.println("Adding temporary atoms...");
            for (var i = 0; i < 50; i++) cog.add(cog.S("Temp_" + UUID.randomUUID().toString().substring(0, 6)));
            var sizeBefore = cog.mem.size();
            System.out.println("Memory size before maintenance: " + sizeBefore);
            System.out.println("Waiting for maintenance cycle...");
            Thread.sleep(FORGET_PERIOD_MS + 2000); // Wait for scheduler
            var sizeAfter = cog.mem.size();
            System.out.println("Memory size after maintenance: " + sizeAfter + " (Target reduction if size > " + cog.mem.capacity + ")");

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
        return add(E(SYM_EQ, premise, conclusion));
    }

    public Expr TYPE(Atom instance, Atom type) {
        return add(E(SYM_COLON, instance, type));
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
     * Runs the agent loop within a specified game and goal.
     */
    public void runAgent(Game g, Atom goal, int maxCycles) {
        this.game = g; // Make game accessible to Is functions via env()
        var agent = new Agent(this);
        try {
            agent.run(g, goal, maxCycles); // Delegate to agent's run method
        } finally {
            this.game = null; // Clear reference afterwards
        }
    }

    /**
     * Shuts down the background maintenance scheduler.
     */
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
    private void maintenance() {
        mem.decayAndForget();
    }

    /**
     * Retrieve the current game game (used by Is functions).
     */
    @Deprecated
    public Optional<Game> env() {
        return Optional.ofNullable(game);
    }

    // --- Core Data Structures ---

    /**
     * Represents the type of an Atom.
     */
    public enum AtomType {SYM, VAR, EXPR, IS}

    /**
     * Base sealed interface for all symbolic atoms in the Cog system.
     * Atoms are immutable by design.
     */
    public sealed interface Atom {
        String id(); // Unique identifier string (used for hashing, equality, storage key)

        AtomType type(); // The type of the atom

        // casting methods (use with caution or instanceof checks)
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
        boolean equals(Object other); // Must be implemented based on id()

        @Override
        int hashCode(); // Must be implemented based on id()

        @Override
        String toString(); // Human-readable representation (often same as id())
    }

    /**
     * Interface for the external game the Agent interacts with.
     */
    public interface Game {
        List<Atom> perceive(Cog cog); // Get current perceptions as Atoms

        List<Atom> actions(Cog cog, Atom currentState); // Get available actions from a state

        Act exe(Cog cog, Atom action); // Execute action, return outcome (new percepts, reward)

        boolean isRunning(); // Check if the game simulation should continue
    }

    /**
     * Represents a Symbol atom (a named constant).
     */
    public record Sym(String name) implements Atom {
        private static final ConcurrentMap<String, Sym> SYMBOL_CACHE = new ConcurrentHashMap<>();

        public static Sym of(String name) {
            return SYMBOL_CACHE.computeIfAbsent(name, Sym::new);
        } // Canonical instances

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
            return this == o || (o instanceof Sym s && name.equals(s.name));
        }
    }

    /**
     * Represents a Variable atom (used in patterns and rules).
     */
    public record Var(String name) implements Atom {
        public Var {
            if (name.startsWith(VAR_PREFIX)) throw new IllegalArgumentException("Var name excludes prefix");
        } // Validate name

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

    /**
     * Represents an Expression atom (a sequence of other atoms, typically function application or relation).
     */
    public record Expr(String id, List<Atom> children) implements Atom {
        private static final ConcurrentMap<List<Atom>, String> idCache = new ConcurrentHashMap<>(); // Cache IDs for performance

        // Construct with immutable copy of children and compute/cache ID
        public Expr(List<Atom> inputChildren) {
            this(computeId(List.copyOf(inputChildren)), List.copyOf(inputChildren));
        }

        // Private constructor used by canonicalizing constructor
        public Expr(String id, List<Atom> children) {
            this.id = id;
            this.children = children;
        }

        private static String computeId(List<Atom> childList) {
            return idCache.computeIfAbsent(childList, Expr::computeIdInternal);
        }

        private static String computeIdInternal(List<Atom> childList) {
            return "(" + childList.stream().map(Atom::id).collect(Collectors.joining(" ")) + ")";
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

        // Equality based on computed ID, assuming robust computation
        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof Expr ea && id.equals(ea.id));
        }
    }

    // --- Metadata Records ---

    /**
     * Represents a "Grounded" or "Interpreted" Symbol atom, bridging MeTTa to Java objects or functions.
     * Can hold a Java value directly, or a Java function to be executed by the interpreter.
     * Immutable value/function reference, but the underlying Java object might be mutable.
     */
    public record Is<T>(String id, @Nullable T value,
                        @Nullable Function<List<Atom>, Optional<Atom>> fn) implements Atom {
        // Constructor for value-holding Is atoms
        public Is(T value) {
            this(deriveId(value), value, null);
        }

        // Constructor allowing explicit ID override for values (less common)
        public Is(String id, T value) {
            this(id, value, null);
        }

        // Constructor for function-holding Is atoms (primitive operations)
        public Is(String name, Function<List<Atom>, Optional<Atom>> fn) {
            this(name, null, fn);
        } // ID is the function name

        // Derives a reasonably stable ID based on the wrapped value type and content.
        private static <T> String deriveId(T value) {
            if (value == null) return "is:null:null";
            var typeName = value.getClass().getSimpleName();
            String valuePart;
            // Special handling for common types to produce readable IDs
            if (value instanceof String s) {
                typeName = "String";
                valuePart = s.length() < 30 ? s : s.substring(0, 27) + "...";
            } else if (value instanceof Double d) {
                typeName = "Double";
                valuePart = d.toString();
            } // Use Double for consistency
            else if (value instanceof Integer i) {
                typeName = "Integer";
                valuePart = i.toString();
            } else if (value instanceof Boolean b) {
                typeName = "Boolean";
                valuePart = b.toString();
            } else if (value instanceof Atom a) {
                typeName = "Atom";
                valuePart = a.id();
            } else if (value instanceof List<?> list) {
                typeName = "List";
                valuePart = "[" + list.stream().map(o -> o instanceof Atom a ? a.id() : String.valueOf(o)).limit(3).collect(Collectors.joining(",")) + (list.size() > 3 ? ",..." : "") + "]";
            }
            // Generic fallback using class name and hashcode for uniqueness estimate
            else {
                var valStr = String.valueOf(value);
                valuePart = (valStr.length() < 30 && !valStr.contains("@")) ? valStr : value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value));
            }
            return "is:" + typeName + ":" + valuePart;
        }

        @Override
        public AtomType type() {
            return AtomType.IS;
        }

        public boolean isFn() {
            return fn != null;
        } // Check if this Is atom represents a function

        public Optional<Atom> apply(List<Atom> args) {
            return isFn() ? fn.apply(args) : empty();
        } // Apply the function if present

        @Override
        public String toString() {
            if (isFn()) return "IsFn<" + id + ">";
            // Special string formatting for quotes and escapes
            if (value instanceof String s)
                return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\"";
            if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
            if (value instanceof Atom a) return a.toString(); // Show wrapped Atom directly
            // Custom formatting for lists of Atoms
            if (value instanceof List<?> list && !list.isEmpty() && list.stream().allMatch(o -> o instanceof Atom)) {
                return "Is<List:" + list.stream().map(Object::toString).limit(5).collect(Collectors.joining(",", "[", list.size() > 5 ? ",...]" : "]")) + ">";
            }
            // Generic fallback for other Java objects
            return id; // Use the derived ID as the default representation
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof Is<?> ga && id.equals(ga.id));
        } // Equality based on ID
    }

    /**
     * Immutable record holding metadata associated with an Atom.
     */
    public record Value(Truth truth, Pri pri, Time access) {
        public static final Value DEFAULT = new Value(Truth.UNKNOWN, Pri.DEFAULT, Time.DEFAULT);

        public Value withTruth(Truth nt) {
            return new Value(nt, pri, access);
        }

        public Value withPri(float sti, float lti) {
            return withPri(new Pri(sti, lti));
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

        // Boost Pri and update access time
        public Value boost(float amount, long now) {
            return new Value(truth, pri.boost(amount), new Time(now));
        }

        // Decay Pri and update access time
        public Value decay(long now) {
            return new Value(truth, pri.decay(), new Time(now));
        }

        // Calculates current priority considering recency (time since last access)
        public double getCurrentPri(long now) {
            // Simplified recency factor: decays significantly over ~3 maintenance cycles
            var timeSinceAccess = Math.max(0, now - access.time());
            var recencyFactor = Math.exp(-timeSinceAccess / (double) (FORGET_PERIOD_MS * 3));
            return pri.getCurrent(recencyFactor) * truth.confidence(); // Modulate by confidence
        }

        // Simple weighted truth measure
        public double getWeightedTruth() {
            return truth.strength * truth.confidence();
        }

        @Override
        public String toString() {
            return truth + " " + pri + " " + access;
        }
    }

    /**
     * Immutable record representing probabilistic truth (strength and confidence/count).
     */
    public record Truth(double strength, double count) {
        public static final Truth TRUE = new Truth(1.0, 10.0); // High confidence true
        public static final Truth FALSE = new Truth(0.0, 10.0); // High confidence false
        public static final Truth UNKNOWN = new Truth(0.5, 0.1); // Low confidence unknown

        public Truth {
            strength = unitize(strength);
            count = Math.max(0, count);
        } // Enforce bounds

        // Sigmoid-like confidence based on count
        public double confidence() {
            return count / (count + TRUTH_DEFAULT_SENSITIVITY);
        }

        // Merge this truth value with another using weighted averaging based on counts
        public Truth merge(Truth other) {
            if (other == null || other.count == 0) return this;
            if (this.count == 0) return other;
            var totalCount = this.count + other.count;
            var mergedStrength = (this.strength * this.count + other.strength * other.count) / totalCount;
            return new Truth(mergedStrength, totalCount); // Confidence increases with total count
        }

        @Override
        public String toString() {
            return String.format("%%%.3f,%.2f%%", strength, count);
        }
    }

    /**
     * Immutable record representing priority (Short-Term Importance and Long-Term Importance).
     */
    public record Pri(float sti, float lti) { // Kept in Java for performance
        public static final Pri DEFAULT = new Pri(PRI_INITIAL_STI, PRI_INITIAL_STI * PRI_INITIAL_LTI_FACTOR);

        public Pri {
            sti = unitize(sti);
            lti = unitize(lti);
        } // Enforce bounds [0,1]

        // Apply exponential decay to STI and LTI, transferring some decayed STI to LTI
        public Pri decay() {
            var decayedSti = sti * (1 - PRI_STI_DECAY_RATE);
            var stiToLtiTransfer = sti * PRI_STI_DECAY_RATE * PRI_STI_TO_LTI_RATE;
            var decayedLti = lti * (1 - PRI_LTI_DECAY_RATE) + stiToLtiTransfer;
            return new Pri(unitize(decayedSti), unitize(decayedLti));
        }

        // Boost STI and proportionally boost LTI based on the STI gain
        public Pri boost(float amount) {
            if (amount <= 0) return this; // Only boost with positive amount
            var boostedSti = unitize(sti + amount);
            var stiGain = boostedSti - sti; // Actual increase in STI after unitize
            var ltiBoostFactor = PRI_STI_TO_LTI_RATE * amount; // LTI boost related to impulse amount
            var boostedLti = unitize(lti + stiGain * ltiBoostFactor); // Boost LTI based on STI *gain* and factor
            return new Pri(boostedSti, boostedLti);
        }

        // Calculate current effective priority, weighted by recency factor
        public double getCurrent(double recencyFactor) {
            return sti * recencyFactor * 0.6 + lti * 0.4;
        } // Weighted mix of STI (recent) and LTI (stable)

        @Override
        public String toString() {
            return String.format("$%.3f,%.3f", sti, lti);
        }
    }

    // --- Core Engine Components ---

    /**
     * Immutable record representing logical time (access timestamp).
     */
    public record Time(long time) {
        public static final Time DEFAULT = new Time(0L);

        @Override
        public String toString() {
            return "@" + time;
        }
    }

    /**
     * Manages Atom storage, indexing, metadata (Value), and forgetting mechanism.
     * Uses concurrent collections for thread safety and AtomicReference for metadata updates.
     */
    public static class Mem {
        private final ConcurrentMap<String, Atom> atomsById = new ConcurrentHashMap<>(1024); // Canonical Atoms by ID
        private final ConcurrentMap<Atom, AtomicReference<Value>> atoms = new ConcurrentHashMap<>(1024); // Atom -> Metadata Ref
        private final ConcurrentMap<Atom, Set<String>> indexByHead = new ConcurrentHashMap<>(); // Head Atom -> Set<Expr ID>
        private final Supplier<Long> timeSource; // Source for logical time
        private int capacity;

        public Mem(Supplier<Long> timeSource, int capacity) {
            this.timeSource = timeSource;
            this.capacity = capacity;
        }

        /**
         * Adds an atom, ensuring canonical instance and initializes/updates metadata.
         */
        public <A extends Atom> A add(A atom) {
            var _atom = atomsById.computeIfAbsent(atom.id(), id -> atom); // Ensure canonical instance
            var now = timeSource.get();
            var valueRef = atoms.computeIfAbsent(_atom, k -> { // Initialize if new
                var initVal = Value.DEFAULT.withPri(new Pri(PRI_INITIAL_STI, PRI_INITIAL_STI * PRI_INITIAL_LTI_FACTOR)).updateTime(now);
                updateIndices(k); // Index expressions when first added
                return new AtomicReference<>(initVal);
            });
            valueRef.updateAndGet(v -> v.boost(PRI_ACCESS_BOOST * 0.1f, now)); // Slight boost on add/access
            checkMemoryAndTriggerForgetting(); // Check if forgetting cycle needed
            return _atom.getClass() == atom.getClass() ? (A) _atom : atom;
        }

        public Optional<Atom> atom(String id) {
            return Optional.ofNullable(atomsById.get(id)).map(this::access);
        }

        public Optional<Atom> atom(Atom atom) {
            return Optional.ofNullable(atomsById.get(atom.id())).map(this::access);
        }

        public Optional<Value> value(Atom atom) {
            return atom(atom).flatMap(key -> Optional.ofNullable(atoms.get(key))).map(AtomicReference::get);
        }

        /**
         * boost, and get
         */
        private Atom access(Atom atom) {
            updateValue(atom, v -> v.boost(PRI_ACCESS_BOOST, timeSource.get()));
            return atom;
        }

        public Value valueOrDefault(Atom atom) {
            return value(atom).orElse(Value.DEFAULT);
        }

        /**
         * Atomically updates the Value associated with an atom.
         */
        public void updateValue(Atom atom, Function<Value, Value> updater) {
            var canonicalAtom = atomsById.get(atom.id());
            if (canonicalAtom == null) return; // Atom not in memory
            var valueRef = atoms.get(canonicalAtom);
            if (valueRef != null) {
                long now = timeSource.get();
                valueRef.updateAndGet(current -> {
                    var updated = updater.apply(current).updateTime(now); // Apply update and set time
                    var confDiff = updated.truth.confidence() - current.truth.confidence();
                    // Boost significantly if confidence increased substantially
                    var boost = (confDiff > TRUTH_REVISION_CONFIDENCE_THRESHOLD) ? (float) (PRI_BOOST_ON_REVISION_MAX * confDiff) : 0;
                    return boost > 0 ? updated.boost(boost, now) : updated;
                });
            }
        }

        public void updateTruth(Atom atom, Truth newTruth) {
            updateValue(atom, v -> v.withTruth(v.truth.merge(newTruth)));
        }

        public void boost(Atom atom, float amount) {
            updateValue(atom, v -> v.boost(amount, timeSource.get()));
        }

        // Convenience methods for creating and adding Atoms
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
            return add(new Is<>(name, fn));
        }

        /**
         * Performs pattern matching query against atoms in memory.
         */
        public List<Answer> query(Atom pattern) {
            var queryPattern = add(pattern); // Ensure pattern is canonical
            boost(queryPattern, PRI_ACCESS_BOOST * 0.2f); // Boost the query pattern itself

            var unification = new Unify(this);
            var checkCount = new AtomicLong(0);
            var maxChecks = Math.min(5000 + size() / 10, 15000); // Limit unification checks

            // Optimize candidate selection based on pattern structure
            return getCandidateStream(queryPattern)
                    .limit(maxChecks * 5) // Limit input stream size before filtering/unification
                    .distinct() // Avoid redundant checks on same candidate
                    .filter(candidate -> valueOrDefault(candidate).truth.confidence() >= TRUTH_MIN_CONFIDENCE_MATCH) // Pre-filter low confidence
                    .map(candidate -> {
                        // Return null if unification fails
                        return checkCount.incrementAndGet() > maxChecks ? null :
                                unification.unify(queryPattern, candidate, Bind.EMPTY).map(bind -> {
                                    boost(candidate, PRI_ACCESS_BOOST); // Boost matched atom
                                    return new Answer(candidate, bind); // Create Answer object
                                }).orElse(null); // Stop if max checks exceeded
                    })
                    .filter(Objects::nonNull) // Filter out failed unifications/limit exceeded
                    .limit(INTERPRETER_MAX_RESULTS * 2)
                    .sorted(Comparator.comparingDouble((Answer ans) ->
                            valueOrDefault(ans.resultAtom()).getWeightedTruth()).reversed()) // Sort final results by weighted truth of the matched atom
                    .limit(INTERPRETER_MAX_RESULTS)
                    .toList();
        }

        // Selects potential matching candidates based on the query pattern structure
        private Stream<Atom> getCandidateStream(Atom queryPattern) {
            return switch (queryPattern) {
                // If pattern is Expr with non-Var head, use index
                case Expr pExpr when pExpr.head() != null && !(pExpr.head() instanceof Var) -> Stream.concat(
                        indexByHead.getOrDefault(pExpr.head(), Set.of()).stream() // Get indexed IDs
                                .map(this::atom).flatMap(Optional::stream), // Fetch corresponding Atoms
                        value(queryPattern).isPresent() ? Stream.of(queryPattern) : Stream.empty() // Include pattern itself if it exists
                );
                // If pattern is Var, match against everything (potential performance issue)
                case Var v -> atoms.keySet().stream();
                // If pattern is Sym, Is, or Expr with Var head, check directly or broad scan
                default -> {
                    Stream<Atom> directMatch = value(queryPattern).isPresent() ? Stream.of(queryPattern) : Stream.empty();
                    // If Expr with Var head, needs to scan all Exprs (slow path)
                    Stream<Atom> broadScan = (queryPattern instanceof Expr pExpr && pExpr.head() instanceof Var) ?
                            atoms.keySet().stream().filter(a -> a.type() == AtomType.EXPR) : Stream.empty();
                    yield Stream.concat(directMatch, broadScan);
                }
            };
        }

        /**
         * Decays Pri and removes low-Pri atoms ("forgetting"). Triggered periodically.
         *  TODO use a 'busy' AtomicBoolean as a mutex for 1-thread decaying at a time, avoiding synchronization
         */
        synchronized void decayAndForget() {
            final long now = timeSource.get();
            var initialSize = atoms.size();
            if (initialSize == 0) return;

            final var currentForgetThreshold = PRI_MIN_FORGET_THRESHOLD;
            final var currentMaxMemTrigger = capacity;
            final var currentTargetFactor = FORGETTING_TARGET_MEM_SIZE_FACTOR;
            final var targetSize = currentMaxMemTrigger * currentTargetFactor / 100;

            List<Atom> victims = new ArrayList<>(initialSize / 10); // Preallocate estimate
            var decayCount = 0;

            // Decay all atoms and identify low-priority candidates for removal
            for (var entry : atoms.entrySet()) {
                var atom = entry.getKey();
                var valueRef = entry.getValue();
                var decayedValue = valueRef.updateAndGet(v -> v.decay(now)); // Decay and update time
                decayCount++;
                // Check if atom is eligible for removal (not protected, below threshold)
                var isProtected = atom instanceof Var || (atom instanceof Sym s && PROTECTED_SYMBOLS.contains(s.name));
                if (!isProtected && decayedValue.getCurrentPri(now) < currentForgetThreshold) {
                    victims.add(atom);
                }
            }

            var removedCount = 0;
            var memoryPressure = initialSize > currentMaxMemTrigger;
            var needsTrimming = initialSize > targetSize; // Need to reduce size even if not under pressure threshold

            var numVictims = victims.size();

            // Perform removal if memory pressure exists, or target size exceeded, or significant low-pri fraction
            if (numVictims > 0 && (memoryPressure || needsTrimming)) {
                // Sort candidates by priority (lowest first) to remove least important ones
                victims.sort(Comparator.comparingDouble(atom -> valueOrDefault(atom).getCurrentPri(now)));
                // Determine how many to remove: reduce to target size if over trigger, else remove all below threshold
                var removalTargetCount = Math.max(0, initialSize - targetSize);
                var actuallyRemoved = 0;
                for (var i = 0; i < numVictims && actuallyRemoved < removalTargetCount; i++) {
                    var atomToRemove = victims.get(i);
                    // Re-check threshold just before removal (in case of concurrent updates, though unlikely in sync block)
                    if (valueOrDefault(atomToRemove).getCurrentPri(now) < currentForgetThreshold) {
                        if (_remove(atomToRemove)) actuallyRemoved++;
                    }
                }
                removedCount = actuallyRemoved;
            }

            if (removedCount > 0 || (decayCount > 0 && initialSize > 10)) { // Log if changes occurred
                System.out.printf("Memory Maintenance: Decayed %d atoms. Removed %d low-pri atoms (Threshold=%.3f). Size %d -> %d.%n",
                        decayCount, removedCount, currentForgetThreshold, initialSize, atoms.size());
            }
        }

        // Internal removal logic: removes from all maps/indices
        boolean _remove(Atom atom) {
            if (atoms.remove(atom) != null) {
                atomsById.remove(atom.id());
                removeIndices(atom); // Remove from expression head index
                return true;
            }
            return false;
        }

        // Asynchronously trigger forgetting if memory usage exceeds threshold
        private void checkMemoryAndTriggerForgetting() {
            if (atoms.size() > capacity) {
                CompletableFuture.runAsync(this::decayAndForget);
            }
        }

        // Index expressions by their head atom (if not a variable)
        private void updateIndices(Atom atom) {
            if (atom instanceof Expr e && e.head() != null && !(e.head() instanceof Var)) {
                indexByHead.computeIfAbsent(e.head(), h -> new ConcurrentSkipListSet<>()).add(atom.id());
            }
        }

        // Remove atom ID from head index
        private void removeIndices(Atom atom) {
            if (atom instanceof Expr e && e.head() != null && !(e.head() instanceof Var)) {
                indexByHead.computeIfPresent(e.head(), (k, v) -> {
                    v.remove(atom.id());
                    return v.isEmpty() ? null : v; // Remove set if empty
                });
            }
        }

        public int size() {
            return atoms.size();
        }
    }

    /**
     * Immutable record representing unification bindings (Variable -> Atom).
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
                if (next == null) return Optional.of(v); // Chain ends in unbound var
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
     * Immutable record representing a query answer (matched atom and bindings).
     */
    public record Answer(Atom resultAtom, Bind bind) {
    }

    /**
     * Performs unification between two atoms, producing bindings if successful.
     * Uses an iterative, stack-based approach for efficiency and tail-call optimization avoidance.
     */
    public static class Unify {
        private final Mem space; // Needed for expr construction during substitution

        public Unify(Mem space) {
            this.space = space;
        }

        /**
         * Unifies pattern and instance, returning bindings or empty Optional.
         */
        public Optional<Bind> unify(Atom pattern, Atom instance, Bind initialBind) {
            Deque<Pair<Atom, Atom>> stack = new ArrayDeque<>();
            stack.push(new Pair<>(pattern, instance));
            var currentBinds = initialBind;

            while (!stack.isEmpty()) {
                var task = stack.pop();
                var p = subst(task.a, currentBinds); // Apply current bindings to pattern
                var i = subst(task.b, currentBinds); // Apply current bindings to instance

                if (p.equals(i)) continue; // Already match

                switch (p) {
                    // Case 1: Pattern is a variable
                    case Var pVar -> {
                        if (containsVar(i, pVar)) return empty(); // Occurs check fail
                        var updatedBinds = mergeBind(currentBinds, pVar, i);
                        if (updatedBinds == null) return empty(); // Conflicting bind
                        currentBinds = updatedBinds;
                    }
                    // Case 2: Instance is a variable (can happen if unifying patterns)
                    case Atom _ when i instanceof Var iVar -> { // Check i type after p switch
                        if (containsVar(p, iVar)) return empty(); // Occurs check fail
                        var updatedBinds = mergeBind(currentBinds, iVar, p);
                        if (updatedBinds == null) return empty(); // Conflicting bind
                        currentBinds = updatedBinds;
                    }
                    // Case 3: Both are expressions
                    case Expr pExpr when i instanceof Expr iExpr -> {
                        var pChildren = pExpr.children;
                        var iChildren = iExpr.children;
                        if (pChildren.size() != iChildren.size()) return empty(); // Arity mismatch
                        // Push children pairs onto stack (reverse order for correct processing)
                        for (var j = pChildren.size() - 1; j >= 0; j--) {
                            stack.push(new Pair<>(pChildren.get(j), iChildren.get(j)));
                        }
                    }
                    // Case 4: Mismatch (Sym vs Expr, different Syms, different Is, etc.)
                    default -> {
                        return empty();
                    }
                }
            }
            return Optional.of(currentBinds); // Successful unification
        }

        /**
         * Applies bindings recursively to an atom. Returns canonical atom from space.
         */
        public Atom subst(Atom atom, Bind bind) {
            if (bind.isEmpty()) return atom; // Quick exit

            return switch (atom) {
                case Var var -> bind.getRecursive(var)
                        .map(val -> val == var ? var : subst(val, bind)) // Recurse on resolved value, avoid self-recursion
                        .orElse(var); // Return var if unbound or cycle
                case Expr expr -> {
                    var changed = false;
                    List<Atom> newChildren = new ArrayList<>(expr.children.size());
                    for (var child : expr.children) {
                        var substChild = subst(child, bind);
                        if (child != substChild) changed = true; // Reference check sufficient
                        newChildren.add(substChild);
                    }
                    yield changed ? space.expr(newChildren) : expr; // Use space.expr for canonicalization
                }
                default -> atom; // Sym, Is are constants w.r.t. substitution
            };
        }

        // Checks if 'var' occurs within 'expr' (prevents infinite recursion).
        private boolean containsVar(Atom expr, Var var) {
            return switch (expr) {
                case Var v -> v.equals(var);
                case Expr e -> e.children.stream().anyMatch(c -> containsVar(c, var));
                default -> false;
            };
        }

        // Merges a new binding (var=value) into existing bindings. Handles conflicts via recursive unification. Returns null on conflict.
        private @Nullable Bind mergeBind(Bind current, Var var, Atom value) {
            var existingBindOpt = current.getRecursive(var);
            if (existingBindOpt.isPresent()) { // Var already bound?
                var existingValue = existingBindOpt.get();
                if (existingValue.equals(value)) return current; // New binding is redundant
                // Conflict: Unify the existing value with the new value.
                return unify(existingValue, value, current).orElse(null); // Return null if they don't unify
            } else { // New binding
                var m = new HashMap<>(current.map());
                m.put(var, value);
                return new Bind(m);
            }
        }
    }

    /**
     * Parses MeTTa text into Atoms using a simple tokenizer and recursive descent parser.
     */
    private static class MettaParser {
        private final Cog cog; // To create atoms using canonical methods (cog.S, cog.V, cog.E, cog.IS)

        MettaParser(Cog cog) {
            this.cog = cog;
        }

        // Parses common symbols, numbers, bools directly into canonical Atoms
        private Atom parseSymbolOrIs(String text) {
            return switch (text) {
                case "True" -> SYM_TRUE;
                case "False" -> SYM_FALSE;
                case "Nil" -> SYM_NIL;
                default -> { // Try parsing as number, fallback to symbol
                    try {
                        yield cog.IS(Double.parseDouble(text));
                    } // Use Double for all numbers
                    catch (NumberFormatException e) {
                        yield cog.S(text);
                    }
                }
            };
        }

        // Unescapes string content from MeTTa representation
        private String unescapeString(String s) {
            return s.replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\");
        }

        // Tokenizes the input MeTTa string
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
                } else if (c == '(') {
                    tokens.add(new Token(TokenType.LPAREN, "(", line, startCol));
                    i++;
                    col++;
                } else if (c == ')') {
                    tokens.add(new Token(TokenType.RPAREN, ")", line, startCol));
                    i++;
                    col++;
                } else if (c == ';') {
                    while (i < len && text.charAt(i) != '\n') i++;
                } // Skip comment until newline or EOF
                else if (c == '"') { // String literal
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
                } else { // Symbol or Number
                    var start = i;
                    while (i < len && !Character.isWhitespace(text.charAt(i)) && !"();".contains(String.valueOf(text.charAt(i)))) {
                        i++;
                        col++;
                    }
                    tokens.add(new Token(TokenType.SYMBOL, text.substring(start, i), line, startCol)); // Initially mark as SYMBOL
                }
            }
            return tokens;
        }

        // Recursive descent parser using token iterator
        private Atom parseAtomFromTokens(PeekableIterator<Token> it) {
            if (!it.hasNext()) throw new MettaParseException("Unexpected EOF during parsing");
            var token = it.next();
            return switch (token.type) {
                case LPAREN -> parseExprFromTokens(it); // Recursively parse expression
                case VAR -> cog.V(token.text.substring(VAR_PREFIX.length())); // Create Var atom
                case SYMBOL -> parseSymbolOrIs(token.text); // Create Symbol or Is<Number/Bool>
                case STRING ->
                        cog.IS(unescapeString(token.text.substring(1, token.text.length() - 1))); // Create Is<String>
                case RPAREN -> throw new MettaParseException("Unexpected ')' at line " + token.line + ":" + token.col);
                // EOF should be handled by hasNext() checks before calling
                case EOF -> throw new MettaParseException("Internal Error: Reached EOF token during parsing");
            };
        }

        // Parses tokens within parentheses into an Expr atom
        private Expr parseExprFromTokens(PeekableIterator<Token> it) {
            List<Atom> children = new ArrayList<>();
            while (true) {
                if (!it.hasNext()) throw new MettaParseException("Unterminated expression, missing ')'");
                var nextToken = it.peek();
                if (nextToken.type == TokenType.RPAREN) {
                    it.next();
                    return cog.E(children);
                } // End of expression
                children.add(parseAtomFromTokens(it)); // Parse child atom recursively
            }
        }

        /**
         * Parses a single MeTTa expression from text.
         */
        public Atom parse(String text) {
            var tokens = tokenize(text);
            if (tokens.isEmpty()) throw new MettaParseException("Empty input or input contains only comments");
            var it = new PeekableIterator<>(tokens.iterator());
            var result = parseAtomFromTokens(it);
            if (it.hasNext()) throw new MettaParseException("Extra token(s) found after parsing atom: " + it.peek());
            return result;
        }

        /**
         * Parses multiple MeTTa expressions from text (e.g., a script file).
         */
        public List<Atom> parseAll(String text) {
            var tokens = tokenize(text);
            List<Atom> results = new ArrayList<>();
            var it = new PeekableIterator<>(tokens.iterator());
            while (it.hasNext()) {
                results.add(parseAtomFromTokens(it));
            }
            return results;
        }

        private enum TokenType {LPAREN, RPAREN, SYMBOL, VAR, STRING, EOF}

        private record Token(TokenType type, String text, int line, int col) {
        }

        // Helper iterator that allows peeking at the next element
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

    /**
     * Custom exception for MeTTa parsing errors.
     */
    public static class MettaParseException extends RuntimeException {
        public MettaParseException(String message) {
            super(message);
        }
    }

    /**
     * General purpose immutable pair record.
     */
    private record Pair<A, B>(A a, B b) {
    }

    /**
     * Initializes core symbols, Is functions (bridging to Java), and loads bootstrap MeTTa rules.
     */
    private static class Core {
        // Core MeTTa rules loaded at startup. Defines basic types, links operators, and includes basic agent logic.
        private static final String CORE_METTA_RULES = """
                ; --- Basic Types & Syntax ---
                (: = Type) (: : Type) (: -> Type) (: Type Type)
                (: True Type) (: False Type) (: Nil Type) (: Number Type) (: String Type) (: JavaObject Type)
                (: Bool Type) (: Atom Type) (: List Type) (: Fn Type)
                (: State Type) (: Action Type) (: Goal Type) (: Utility Type) (: Implies Type) (: Seq Type) (: Act Type)
                
                ; Type Assertions for constants
                (: True Bool) (: False Bool) (: Nil List)
                
                ; Type assertions for core Is functions (helps reasoning about the system itself)
                (: match Fn) (: eval Fn) (: add-atom Fn) (: remove-atom Fn) (: get-value Fn) (: &self Fn)
                (: _+ Fn) (: _- Fn) (: _* Fn) (: _/ Fn) (: _== Fn) (: _> Fn) (: _< Fn)
                (: Concat Fn) (: If Fn) (: Let Fn) (: IsEmpty Fn) (: IsNil Fn) (: RandomFloat Fn)
                (: RandomChoice Fn)
                (: GetEnv Fn) (: AgentPerceive Fn) (: AgentAvailableActions Fn) (: AgentExecute Fn)
                (: JavaNew Fn) (: JavaCall Fn) (: JavaStaticCall Fn) (: JavaField Fn) (: JavaProxy Fn)
                
                ; --- Basic Operations & Logic ---
                ; Link operators to internal Is primitives (prefixed with _)
                (= (+ $a $b) (_+ $a $b))
                (= (- $a $b) (_- $a $b))
                (= (* $a $b) (_* $a $b))
                (= (/ $a $b) (_/ $a $b))
                (= (== $a $b) (_== $a $b))
                (= (> $a $b) (_> $a $b))
                (= (< $a $b) (_< $a $b))
                
                ; Basic List processing helpers (can be expanded)
                (= (FirstNumOrZero ($num . $rest)) $num) :- (: $num Number)
                (= (FirstNumOrZero ($other . $rest)) (FirstNumOrZero $rest))
                (= (FirstNumOrZero $num) $num) :- (: $num Number)
                (= (FirstNumOrZero Nil) 0.0)
                (= (FirstNumOrZero $any) 0.0) ; Default if not list or number
                
                ; Peano Arithmetic Example (Illustrative)
                (= (add Z $n) $n)
                (= (add (S $m) $n) (S (add $m $n)))
                
                ; --- Default Agent Logic ---
                ; Main agent step: Perceive -> Select Action -> Execute -> Learn -> Check Goal (implicitly via loop)
                (= (AgentStep $agent)
                   (AgentLearn $agent (AgentExecute $agent (AgentSelectAction $agent (AgentPerceive $agent)))))
                
                ; Simple learning: Update utility based on reward from Act result
                ; Input: (Act Is<List<Percepts>> Is<Reward>)
                (= (AgentLearn $agent (Act $perceptsIs $rewardIs))
                   (UpdateUtility $agent $rewardIs)) ; Simplified: Doesn't use state/action yet
                
                ; Placeholder utility update: Adds reward to a generic Utility atom for the agent
                (= (UpdateUtility $agent $rewardIs)
                   (Let (= $utilAtom (= (Utility $agent) $val)) ; Pattern to match existing utility
                     (Let (= $currentUtilVal (FirstNumOrZero (match &self $utilAtom $val))) ; Get current value (0 if none)
                       (Let (= $rewardVal (FirstNumOrZero (eval $rewardIs))) ; Evaluate reward Is<Double>
                         (Let (= $newUtil (+ $currentUtilVal $rewardVal))
                           (add-atom (= (Utility $agent) $newUtil))))))) ; Add/replace utility atom
                
                ; Action selection: Choose randomly from available actions (provided as Is<List<Action>>)
                ; Input: Is<List<Percepts>> -> Output: Selected Action Atom
                (= (AgentSelectAction $agent $perceptsIs)
                   (Let (= $state (First $perceptsIs)) ; Assume first percept represents state for simplicity
                     (Let (= $actionsIs (AgentAvailableActions $agent $state)) ; Get available actions Is<List<Action>>
                       (RandomChoice $actionsIs)))) ; Choose one randomly using Is function
                
                ; Helper to get first element (assumes Is<List> or Nil) - basic version
                (= (First ($a . $b)) $a)
                (= (First Nil) Nil)
                (= (First $x) $x) ; Fallback if not a list
                
                ; Goal Check Rule: Check if a specific goal pattern exists in memory for the agent
                (= (CheckGoal $agent $goalPattern)
                   (If (IsEmpty (match &self $goalPattern $goalPattern)) False True)) ; True if pattern matches itself in memory
                
                ; --- End Core Rules ---
                """;
        private final Cog cog;

        Core(Cog cog) {
            this.cog = cog;
            // Assign canonical symbols (retrieved or created via add/S)
            SYM_EQ = cog.S("=");
            SYM_COLON = cog.S(":");
            SYM_ARROW = cog.S("->");
            SYM_TYPE = cog.S("Type");
            SYM_TRUE = cog.S("True");
            SYM_FALSE = cog.S("False");
            SYM_SELF = cog.S("Self");
            SYM_NIL = cog.S("Nil");

            initFn(); // Define bridges to Java functionality (including JVM bridge calls)
            cog.load(CORE_METTA_RULES); // Load core rules & agent logic from MeTTa string

            // Ensure core symbols/types have high confidence/priority
            Stream.concat(PROTECTED_SYMBOLS.stream(), Stream.of("Z", "S", "add")) // Include Peano symbols
                    .map(cog::S) // Convert names to Sym atoms
                    .forEach(sym -> {
                        cog.mem.updateTruth(sym, Truth.TRUE);
                        cog.mem.boost(sym, 1); // Max boost
                    });
        }

        // --- Helpers for Is Functions (Static, reusable logic) ---
        // Extracts Double value from Is<Number>, evaluating atom if necessary.
        private static Optional<Double> numValue(@Nullable Atom atom, Cog cog) {
            Function<Atom, Optional<Double>> extractor = a -> switch (a) {
                case Is<?> is when is.value instanceof Number n -> Optional.of(n.doubleValue());
                default -> empty();
            };
            return Optional.ofNullable(atom).flatMap(a -> extractor.apply(a).or(() -> cog.evalBest(a).flatMap(extractor)));
        }

        // Extracts List<Atom> value from Is<List>. Does NOT evaluate.
        @SuppressWarnings("unchecked")
        static Optional<List<Atom>> listValue(@Nullable Atom atom) {
            return (atom instanceof Is<?> is && is.value instanceof List<?> l && l.stream().allMatch(i -> i instanceof Atom))
                    ? Optional.of((List<Atom>) is.value) : empty();
        }

        // Extracts String value from Is<String> or Sym. Does NOT evaluate.
        static Optional<String> stringValue(@Nullable Atom atom) {
            return switch (atom) {
                case Is<?> is when is.value instanceof String s -> Optional.of(s);
                case Sym s -> Optional.of(s.name);
                default -> empty();
            };
        }

        // Applies a binary double operation to two arguments, evaluating them first.
        private static Optional<Atom> applyNumOp(List<Atom> args, Cog cog, BinaryOperator<Double> op) {
            if (args.size() != 2) return empty();
            var n1Opt = numValue(args.get(0), cog);
            var n2Opt = numValue(args.get(1), cog);
            return n1Opt.flatMap(n1 -> n2Opt.map(n2 -> cog.IS(op.apply(n1, n2)))); // Use flatMap for cleaner optional chaining
        }

        // Applies a binary double predicate to two arguments, evaluating them first.
        private static Optional<Atom> applyNumPred(List<Atom> args, Cog cog, BiPredicate<Double, Double> op) {
            if (args.size() != 2) return empty();
            var n1Opt = numValue(args.get(0), cog);
            var n2Opt = numValue(args.get(1), cog);
            return n1Opt.flatMap(n1 -> n2Opt.map(n2 -> op.test(n1, n2) ? SYM_TRUE : SYM_FALSE));
        }

        /**
         * Defines the `Is` Fn atoms that bridge MeTTa symbols to Java code execution.
         */
        private void initFn() {
            // --- Core Ops ---
            cog.IS("match", args -> (args.size() != 3) ? empty() :
                    Optional.ofNullable((args.getFirst() instanceof Is<?> g && g.value instanceof Mem ts) ? ts : cog.mem) // Get space or default
                            .map(space -> cog.IS(space.query(args.get(1)).stream() // Perform query
                                    .map(ans -> cog.interp.subst(args.get(2), ans.bind())) // Substitute into template
                                    .toList())) // Collect results into Is<List<Atom>>
            );
            cog.IS("eval", args -> args.isEmpty() ? empty() : cog.evalBest(args.getFirst())); // Evaluate atom, return best result
            cog.IS("add-atom", args -> args.isEmpty() ? empty() : Optional.of(cog.add(args.getFirst()))); // Add atom to memory
            cog.IS("remove-atom", args -> Optional.of((!args.isEmpty() && cog.mem._remove(args.getFirst())) ? SYM_TRUE : SYM_FALSE)); // Remove atom
            cog.IS("get-value", args -> args.isEmpty() ? empty() : cog.mem.value(args.getFirst()).map(cog::IS)); // Get Value metadata
            cog.IS("&self", args -> Optional.of(cog.IS(cog.mem))); // Reference to current memory space

            // --- Arithmetic / Comparison (Delegates to helpers using internal _ symbols) ---
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
            cog.IS("If", args -> (args.size() != 3) ? empty() :
                    cog.evalBest(args.getFirst()) // Evaluate condition
                            .map(condResult -> condResult.equals(SYM_TRUE) ? args.get(1) : args.get(2)) // Return unevaluated then/else branch
            );
            cog.IS("Let", args -> (args.size() != 2 || !(args.getFirst() instanceof Expr b) || !b.head().equals(SYM_EQ) || b.children.size() != 3 || !(b.children.get(1) instanceof Var v)) ? empty() :
                    cog.evalBest(b.children.get(2)).map(vv -> // Evaluate value expr
                            cog.unify.subst(args.get(1), new Bind(Map.of(v, vv))) // Substitute var in body and return unevaluated body
                    ));
            cog.IS("IsEmpty", args -> args.isEmpty() ? Optional.of(SYM_TRUE) :
                    cog.evalBest(args.getFirst()).map(res -> // Evaluate arg
                            (res.equals(SYM_NIL) || (res instanceof Is<?> is && is.value instanceof List<?> l && l.isEmpty())) ? SYM_TRUE : SYM_FALSE
                    ));
            cog.IS("IsNil", args -> args.isEmpty() ? Optional.of(SYM_TRUE) :
                    cog.evalBest(args.getFirst()).map(res -> res.equals(SYM_NIL) ? SYM_TRUE : SYM_FALSE)
            );
            cog.IS("RandomFloat", args -> Optional.of(cog.IS(RandomGenerator.getDefault().nextDouble()))); // Random float [0,1)
            cog.IS("RandomChoice", args -> args.stream().findFirst() // Takes one argument (the list)
                    .flatMap(Core::listValue) // Try to get List<Atom> directly from Is<List>
                    .or(() -> args.stream().findFirst().flatMap(cog::evalBest).flatMap(Core::listValue)) // Or evaluate arg then get List<Atom>
                    .filter(list -> !list.isEmpty())
                    .map(list -> list.get(RandomGenerator.getDefault().nextInt(list.size()))) // Choose random element
                    .or(() -> Optional.of(SYM_NIL)) // Return Nil if list empty or arg invalid
            );

            // --- Agent Support (Bridge to game and agent state) ---
            cog.IS("GetEnv", args -> cog.env().map(cog::IS)); // Get Is<Game>
            cog.IS("AgentPerceive", args -> args.isEmpty() ? empty() : // Arg is agent ID (e.g., Self)
                    cog.evalBest(args.getFirst()).flatMap(agentId -> // Requires agent ID
                            cog.env().map(env -> cog.IS(env.perceive(cog)))) // Returns Is<List<PerceptAtoms>>
            );
            cog.IS("AgentAvailableActions", args -> (args.size() != 2) ? empty() : // Args: agent ID, state Atom
                    cog.env().flatMap(env ->
                            cog.evalBest(args.get(1)).map(stateAtom -> // Evaluate state argument
                                    cog.IS(env.actions(cog, stateAtom)))) // Returns Is<List<ActionAtoms>>
            );
            cog.IS("AgentExecute", args -> (args.size() != 2) ? empty() : // Args: agent ID, action Atom
                    cog.env().flatMap(env ->
                            cog.evalBest(args.get(1)).map(actionAtom -> { // Evaluate action argument
                                var actResult = env.exe(cog, actionAtom);
                                // Return structured result: (Act Is<List<Atom>> Is<Double>)
                                return cog.E(cog.S("Act"), cog.IS(actResult.newPercepts()), cog.IS(actResult.reward()));
                            }))
            );
            // JVM functions are registered by the Jvm class constructor
        }
    }

    /**
     * Result of an agent's action execution in the game.
     */
    public record Act(List<Atom> newPercepts, double reward) {
    }

    /**
     * Example minimal Game game for testing agent logic.
     */
    static class SimpleGame implements Game {
        private final Atom posA, posB, posGoal, moveAtoB, moveBtoGoal, moveOther, statePred, selfSym;
        private final Cog cog; // Reference to Cog needed for creating atoms
        private Atom currentStateSymbol; // Internal state of the game

        SimpleGame(Cog c) {
            this.cog = c;
            // Define atoms used in this simple game world
            posA = c.S("Pos_A");
            posB = c.S("Pos_B");
            posGoal = c.S("AtGoal");
            moveAtoB = c.S("Move_A_B");
            moveBtoGoal = c.S("Move_B_Goal");
            moveOther = c.S("Move_Other");
            statePred = c.S("State");
            selfSym = c.S("Self");
            currentStateSymbol = posA; // Start at position A
        }

        // Perception is the agent's current state: (State Self Pos_A), (State Self Pos_B), or (State Self AtGoal)
        @Override
        public List<Atom> perceive(Cog cog) {
            return List.of(cog.E(statePred, selfSym, currentStateSymbol));
        }

        // Available actions depend on the current internal state symbol
        @Override
        public List<Atom> actions(Cog cog, Atom currentStateAtom) {
            // Note: currentStateAtom passed from MeTTa might be the full (State Self Pos_A) expr.
            // Simple demo uses internal stateSymbol directly. A real env would parse stateAtom.
            if (currentStateSymbol.equals(posA)) return List.of(moveAtoB, moveOther);
            else if (currentStateSymbol.equals(posB)) return List.of(moveBtoGoal, moveOther);
            else return List.of(moveOther); // Only 'other' if at goal or invalid state
        }

        // Execute action, update internal state, return outcome
        @Override
        public Act exe(Cog cog, Atom actionSymbol) {
            var reward = -0.1; // Default cost per step
            String logMsg;
            if (currentStateSymbol.equals(posA) && actionSymbol.equals(moveAtoB)) {
                currentStateSymbol = posB;
                reward = 0.1;
                logMsg = "Moved A -> B";
            } else if (currentStateSymbol.equals(posB) && actionSymbol.equals(moveBtoGoal)) {
                currentStateSymbol = posGoal;
                reward = 1.0;
                logMsg = "Moved B -> Goal!";
            } else if (actionSymbol.equals(moveOther)) {
                reward = -0.2;
                logMsg = "Executed 'Move_Other'";
            } else {
                reward = -0.5;
                logMsg = "Invalid action: " + actionSymbol + " from " + currentStateSymbol; // Penalty
            }
            System.out.println("Env: " + logMsg);
            return new Act(perceive(cog), reward); // Return new percepts and reward
        }

        // Game ends when the agent reaches the goal state
        @Override
        public boolean isRunning() {
            return !currentStateSymbol.equals(posGoal);
        }
    }


    /**
     * Bridges MeTTa execution to Java Reflection and MethodHandles, allowing MeTTa
     * code to interact with the JVM. Includes security checks.
     */
    private static class Jvm {
        // Basic security: Allow list for classes/packages (expandable, could be MeTTa-driven)
        private static final Set<String> ALLOWED_CLASSES = Set.of(
                "java.lang.String", "java.lang.Math", "java.util.ArrayList", "java.util.HashMap",
                "java.lang.Double", "java.lang.Integer", "java.lang.Boolean", "java.lang.Object",
                "java.lang.System", // For System.out.println example, etc.
                Cog.class.getName(), Cog.class.getName() + "$Atom", // Allow access to Cog internals if needed cautiously
                "java.lang.Runnable" // For proxy example
        );
        private static final Set<String> ALLOWED_PACKAGES = Set.of("java.lang", "java.util", "java.util.concurrent");
        private final Cog cog; // For creating result Atoms (IS, S, etc.)
        private final MethodHandles.Lookup lookup; // For efficient method access

        public Jvm(Cog c) {
            this.cog = c;
            this.lookup = MethodHandles.lookup();
            // Register the Java bridge Is functions
            c.IS("JavaNew", this::javaNew);
            c.IS("JavaCall", this::javaCall);
            c.IS("JavaStaticCall", this::javaStaticCall);
            c.IS("JavaField", this::javaField);
            c.IS("JavaProxy", this::javaProxy);
        }

        // Check compatibility between formal param type and actual arg type, handling primitive/wrapper boxing.
        private static boolean isTypeCompatible(Class<?> formal, Class<?> actual) {
            if (formal.isAssignableFrom(actual)) return true;
            if (formal.isPrimitive()) return compatibleUnbox(formal, actual);
            if (actual.isPrimitive()) return compatibleBox(formal, actual);
            return false; // Default: not compatible
        }

        private static boolean compatibleUnbox(Class<?> formalPrim, Class<?> actualWrapper) {
            return (formalPrim == int.class && actualWrapper == Integer.class) || (formalPrim == double.class && actualWrapper == Double.class) ||
                    (formalPrim == boolean.class && actualWrapper == Boolean.class) || (formalPrim == long.class && actualWrapper == Long.class) ||
                    (formalPrim == float.class && actualWrapper == Float.class) || (formalPrim == char.class && actualWrapper == Character.class) ||
                    (formalPrim == byte.class && actualWrapper == Byte.class) || (formalPrim == short.class && actualWrapper == Short.class);
        }

        // --- Type Conversion ---

        private static boolean compatibleBox(Class<?> formalWrapper, Class<?> actualPrim) {
            return compatibleUnbox(actualPrim, formalWrapper); // Symmetric relationship
        }

        // --- Security Checks ---
        private boolean isClassAllowed(String className) {
            if (ALLOWED_CLASSES.contains(className)) return true;
            // Check allowed packages (simple prefix check)
            return ALLOWED_PACKAGES.stream().anyMatch(pkg -> className.startsWith(pkg + "."));
            // TODO: Implement more robust security (e.g., load allow/deny lists from MeTTa config)
        }

        private boolean isMemberAllowed(Class<?> clazz, String memberName) {
            // TODO: Add fine-grained method/field level security checks if needed (e.g., deny System.exit)
            return isClassAllowed(clazz.getName()); // Basic check: if class is allowed, assume member is too (for now)
        }

        /**
         * Converts a MeTTa Atom to a best-effort Java Object for reflection calls.
         */
        @Nullable
        private Object m2j(@Nullable Atom x) {
            return switch (x) {
                case null -> null;
                case Atom a when a.equals(SYM_NIL) -> null; // Map Nil to null
                case Is<?> is -> is.value; // Unwrap Is<T>
                case Sym s when s.equals(SYM_TRUE) -> Boolean.TRUE;
                case Sym s when s.equals(SYM_FALSE) -> Boolean.FALSE;
                case Sym s -> s.name; // Represent symbols as names (could be ambiguous)
                case Var v -> v.id(); // Represent vars by ID (e.g., "$x")
                case Expr e -> e; // Pass expressions directly (less common as arg)
            };
        }

        /**
         * Converts a Java Object result from reflection back to a suitable MeTTa Atom.
         */
        private Atom j2m(@Nullable Object x) {
            return switch (x) {
                case null -> SYM_NIL; // Map null back to Nil
                // Avoid double wrapping if Java method already returns an Atom
                case Atom a -> a;
                // Canonical True/False for booleans
                case Boolean b -> b ? SYM_TRUE : SYM_FALSE;
                // Use IS to wrap Java objects, ensuring Numbers become Is<Double> for consistency
                case Number n -> cog.IS(n.doubleValue());
                // Wrap collections/arrays as Is<List<Atom>> if possible
                case List<?> l -> cog.IS(l.stream().map(this::j2m).toList());
                case Object[] arr -> cog.IS(Arrays.stream(arr).map(this::j2m).toList());
                // Fallback: Wrap other objects directly in Is<>
                default -> cog.IS(x);
            };
        }

        /**
         * Determines the Java Class type from a MeTTa Atom for method signature matching.
         */
        private Class<?> argType(@Nullable Atom atom) {
            var obj = m2j(atom);
            return switch (obj) {
                case null -> Object.class; // Use Object for null args
                // Prefer primitive types for matching where appropriate
                case Double v -> double.class;
                case Integer i -> int.class;
                case Long l -> long.class;
                case Float v -> float.class;
                case Boolean b -> boolean.class;
                case Character c -> char.class;
                case Byte b -> byte.class;
                case Short i -> short.class;
                default -> obj.getClass(); // Otherwise, use the object's actual class
            };
        }

        // Finds a class safely, checking allow list.
        @Nullable
        private Class<?> findClass(String className) {
            if (!isClassAllowed(className)) {
                System.err.println("Security: Class not allowed: " + className);
                return null;
            }
            try {
                return Class.forName(className);
            } catch (ClassNotFoundException e) {
                System.err.println("Error: Class not found: " + className);
                return null;
            }
        }

        // Attempts to convert a Java object to a target type if direct assignment fails (basic cases)
        @Nullable
        private Object attemptConversion(Object value, Class<?> t) {
            if (value == null) return null;
            // Basic numeric conversions from Double (common case from MeTTa)
            if (value instanceof Double d) {
                if (t == int.class || t == Integer.class) return d.intValue();
                if (t == long.class || t == Long.class) return d.longValue();
                if (t == float.class || t == Float.class) return d.floatValue();
                if (t == short.class || t == Short.class) return d.shortValue();
                if (t == byte.class || t == Byte.class) return d.byteValue();
            }
            // String to number/boolean (less common, more error prone)
            if (value instanceof String s) {
                try {
                    if (t == double.class || t == Double.class) return Double.parseDouble(s);
                    if (t == int.class || t == Integer.class) return Integer.parseInt(s);
                    if (t == boolean.class || t == Boolean.class) return Boolean.parseBoolean(s);
                    // Add more string conversions if needed
                } catch (NumberFormatException ignored) {
                }
            }
            System.err.println("Warning: Could not convert " + value.getClass().getSimpleName() + " to " + t.getSimpleName());
            return null; // Conversion failed
        }

        // --- Bridge Implementations ---
        // (JavaNew <class_name_string> <arg1> <arg2> ...) -> Is<Object> or Error Symbol
        public Optional<Atom> javaNew(List<Atom> args) {
            if (args.isEmpty()) return Optional.of(cog.S("Error:JavaNew:NoClassName"));
            if (!(m2j(args.getFirst()) instanceof String className))
                return Optional.of(cog.S("Error:JavaNew:ClassNameNotString"));

            var clazz = findClass(className);
            if (clazz == null) return Optional.of(cog.S("Error:JavaNew:ClassNotFound:" + className));

            var constructorArgs = args.subList(1, args.size());
            var javaArgs = constructorArgs.stream().map(this::m2j).toArray();
            var argTypes = constructorArgs.stream().map(this::argType).toArray(Class<?>[]::new);

            try {
                // Find constructor using reflection (simpler than MethodHandles for constructors)
                var constructor = findConstructor(clazz, argTypes);
                if (constructor == null)
                    return Optional.of(cog.S("Error:JavaNew:ConstructorNotFound:" + Arrays.toString(argTypes)));
                if (!isMemberAllowed(clazz, "<init>")) return Optional.of(cog.S("Error:JavaNew:ConstructorNotAllowed"));
                // Adjust args if types didn't match exactly but were found compatible
                var adjustedArgs = adjustArguments(javaArgs, constructor.getParameterTypes());
                var instance = constructor.newInstance(adjustedArgs);
                return Optional.of(j2m(instance));
            } catch (Exception e) { // Handles InstantiationException, IllegalAccessException, InvocationTargetException
                System.err.println("Error: Constructor invocation failed for " + className + ": " + e);
                if (e.getCause() != null) e.getCause().printStackTrace();
                return Optional.of(cog.S("Error:JavaNew:ConstructorFailed:" + e.getClass().getSimpleName()));
            }
        }

        // Common logic for instance and static calls using MethodHandles for performance
        private Optional<Atom> invokeMethod(boolean isStatic, List<Atom> args) {
            var minArgs = isStatic ? 2 : 2; // Static: class, method; Instance: instance, method
            if (args.size() < minArgs) return Optional.of(cog.S("Error:Invoke:InsufficientArgs"));

            Object target = null; // Null for static, instance object for instance call
            Class<?> clazz;
            String methodName;
            int methodArgStartIndex;

            var first = args.getFirst();
            if (isStatic) {
                if (!(m2j(first) instanceof String className))
                    return Optional.of(cog.S("Error:Invoke:ClassNameNotString"));
                clazz = findClass(className);
                if (clazz == null) return Optional.of(cog.S("Error:Invoke:ClassNotFound:" + className));
                if (!(m2j(args.get(1)) instanceof String mName))
                    return Optional.of(cog.S("Error:Invoke:MethodNameNotString"));
                methodName = mName;
                methodArgStartIndex = 2;
            } else { // Instance call
                target = m2j(first);
                if (target == null) return Optional.of(cog.S("Error:Invoke:TargetInstanceNull"));
                clazz = target.getClass(); // Get class from instance
                // Security check on instance's class
                if (!isClassAllowed(clazz.getName()))
                    return Optional.of(cog.S("Error:Invoke:ClassNotAllowed:" + clazz.getName()));
                if (!(m2j(args.get(1)) instanceof String mName))
                    return Optional.of(cog.S("Error:Invoke:MethodNameNotString"));
                methodName = mName;
                methodArgStartIndex = 2;
            }

            if (!isMemberAllowed(clazz, methodName))
                return Optional.of(cog.S("Error:Invoke:MemberNotAllowed:" + methodName));

            var methodArgs = args.subList(methodArgStartIndex, args.size());
            var javaArgs = methodArgs.stream().map(this::m2j).toArray();
            var argTypes = methodArgs.stream().map(this::argType).toArray(Class<?>[]::new);

            try {
                // Find method reflectively first to handle overloading and get exact types
                var m = findMethod(clazz, methodName, argTypes);
                if (m == null) {
                    // Special case: If static call with no args, try static field access
                    return Optional.of(isStatic && methodArgs.isEmpty() ?
                            accessStaticField(clazz, methodName) :
                            cog.S("Error:Invoke:MethodNotFound:" + methodName + Arrays.toString(argTypes)));
                }

                // Get MethodHandle using lookup for potentially better performance
                var h = lookup.unreflect(m);
                // MethodHandles require exact type matching, adjust args if needed (e.g., Double -> int)
                var adjustedArgs = adjustArguments(javaArgs, m.getParameterTypes());

                // Invoke the method handle
                // Bind instance for virtual call
                return Optional.of(j2m((isStatic ? h : h.bindTo(target)).invokeWithArguments(adjustedArgs)));
            } catch (Throwable e) { // Catch Throwable for MethodHandle invoke errors & reflection errors
                System.err.println("Error: Method invocation failed for " + clazz.getName() + "." + methodName + ": " + e);
                if (e instanceof InvocationTargetException ite && ite.getCause() != null)
                    ite.getCause().printStackTrace(); // Unwrap target exception
                else e.printStackTrace();
                return Optional.of(cog.S("Error:Invoke:InvocationFailed:" + e.getClass().getSimpleName()));
            }
        }

        // Handles static field access as a fallback for 0-arg static calls
        private Atom accessStaticField(Class<?> clazz, String fieldName) {
            try {
                var f = clazz.getField(fieldName);
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                    return cog.S("Error:Invoke:FieldNotStatic:" + fieldName);
                else if (!isMemberAllowed(clazz, fieldName)) return cog.S("Error:Invoke:MemberNotAllowed:" + fieldName);
                else return j2m(f.get(null)); // Get static field value
            } catch (NoSuchFieldException e) {
                return cog.S("Error:Invoke:MethodOrFieldNotFound:" + fieldName);
            } // Combined error msg
            catch (IllegalAccessException e) {
                return cog.S("Error:Invoke:FieldAccessDenied:" + fieldName);
            }
        }

        // Helper to find constructor reflectively, handling type compatibility.
        @Nullable
        private java.lang.reflect.Constructor<?> findConstructor(Class<?> clazz, Class<?>[] argTypes) {
            try {
                return clazz.getConstructor(argTypes);
            } // Try exact match first
            catch (NoSuchMethodException e) {
                for (var constructor : clazz.getConstructors()) { // Check all public constructors
                    if (constructor.getParameterCount() == argTypes.length && parametersMatch(constructor.getParameterTypes(), argTypes))
                        return constructor;
                }
                return null; // Not found
            }
        }

        // Helper to find method reflectively, handling type compatibility.
        @Nullable
        private Method findMethod(Class<?> clazz, String methodName, Class<?>[] argTypes) {
            try {
                return clazz.getMethod(methodName, argTypes);
            } // Try exact match first
            catch (NoSuchMethodException e) {
                for (var method : clazz.getMethods()) { // Check all public methods
                    if (method.getName().equals(methodName) && method.getParameterCount() == argTypes.length) {
                        if (parametersMatch(method.getParameterTypes(), argTypes)) return method;
                    }
                }
                return null; // Not found
            }
        }

        // Check if actual arg types are compatible with formal parameter types
        private boolean parametersMatch(Class<?>[] formalTypes, Class<?>[] actualTypes) {
            for (var i = 0; i < formalTypes.length; i++) {
                if (!isTypeCompatible(formalTypes[i], actualTypes[i])) return false;
            }
            return true;
        }

        // Adjust argument values based on formal types (e.g., convert Double to int)
        private Object[] adjustArguments(Object[] args, Class<?>[] formalTypes) {
            if (args.length != formalTypes.length) throw new IllegalArgumentException("Argument count mismatch");
            var adjusted = new Object[args.length];
            for (var i = 0; i < args.length; i++) {
                if (args[i] != null && !isTypeCompatible(formalTypes[i], args[i].getClass())) {
                    adjusted[i] = attemptConversion(args[i], formalTypes[i]);
                    if (adjusted[i] == null && !formalTypes[i].isPrimitive()) // Allow null only for non-primitives if conversion failed
                        adjusted[i] = null;
                    else if (adjusted[i] == null && formalTypes[i].isPrimitive())
                        throw new IllegalArgumentException("Cannot assign null or incompatible type " + (args[i] != null ? args[i].getClass().getSimpleName() : "null") + " to primitive parameter " + formalTypes[i].getSimpleName());
                } else {
                    adjusted[i] = args[i]; // Compatible or already null
                }
            }
            return adjusted;
        }


        // (JavaCall <instance_is> <method_name_string> <arg1> ...) -> Is<Result> or Error Symbol
        public Optional<Atom> javaCall(List<Atom> args) {
            return invokeMethod(false, args);
        }

        // (JavaStaticCall <class_name_string> <method_name_string> <arg1> ...) -> Is<Result> or Error Symbol
        public Optional<Atom> javaStaticCall(List<Atom> args) {
            return invokeMethod(true, args);
        }

        // (JavaField <target> <field_name>) -> Get Value | (JavaField <target> <field_name> <value>) -> Set Value
        public Optional<Atom> javaField(List<Atom> args) {
            if (args.size() < 2 || args.size() > 3) return Optional.of(cog.S("Error:JavaField:InvalidArgs"));

            var targetOrClassAtom = args.get(0);
            var targetOrClass = m2j(targetOrClassAtom);
            if (!(m2j(args.get(1)) instanceof String fieldName))
                return Optional.of(cog.S("Error:JavaField:FieldNameNotString"));

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
                return Optional.of(cog.S("Error:JavaField:InvalidTarget"));
            }

            if (clazz == null) return Optional.of(cog.S("Error:JavaField:ClassNotFound"));
            if (!isMemberAllowed(clazz, fieldName)) return Optional.of(cog.S("Error:JavaField:FieldNotAllowed"));

            try {
                var field = clazz.getField(fieldName); // Public fields only
                if (isStatic != java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                    return Optional.of(cog.S("Error:JavaField:StaticMismatch"));

                if (args.size() == 2) { // Get field
                    return Optional.of(j2m(field.get(instance))); // instance is null for static
                } else { // Set field
                    var valueToSetAtom = args.get(2);
                    var valueToSet = m2j(valueToSetAtom);
                    var adjustedValue = valueToSet;
                    // Attempt conversion if types don't match directly
                    if (valueToSet != null && !isTypeCompatible(field.getType(), valueToSet.getClass())) {
                        adjustedValue = attemptConversion(valueToSet, field.getType());
                        if (adjustedValue == null && field.getType().isPrimitive())
                            return Optional.of(cog.S("Error:JavaField:TypeMismatch:CannotSetPrimitiveNull"));
                        if (adjustedValue == null && valueToSet != null) // Conversion failed
                            return Optional.of(cog.S("Error:JavaField:TypeMismatch:CannotConvert"));
                    } else if (valueToSet == null && field.getType().isPrimitive()) {
                        return Optional.of(cog.S("Error:JavaField:TypeMismatch:CannotSetPrimitiveNull"));
                    }
                    field.set(instance, adjustedValue); // instance is null for static
                    return Optional.of(SYM_TRUE); // Success
                }
            } catch (NoSuchFieldException e) {
                return Optional.of(cog.S("Error:JavaField:NotFound:" + fieldName));
            } catch (IllegalAccessException e) {
                return Optional.of(cog.S("Error:JavaField:AccessDenied:" + fieldName));
            } catch (Exception e) {
                System.err.println("Error: Field operation failed: " + e);
                return Optional.of(cog.S("Error:JavaField:OperationFailed:" + e.getClass().getSimpleName()));
            }
        }

        // (JavaProxy <interface_name_string> <handler_metta_expr>) -> Is<Proxy> or Error Symbol
        public Optional<Atom> javaProxy(List<Atom> args) {
            if (args.size() != 2) return Optional.of(cog.S("Error:JavaProxy:InvalidArgs"));
            if (!(m2j(args.get(0)) instanceof String interfaceName))
                return Optional.of(cog.S("Error:JavaProxy:InterfaceNameNotString"));
            var handlerExprTemplate = args.get(1); // MeTTa atom defining the handler logic

            var iface = findClass(interfaceName);
            if (iface == null || !iface.isInterface())
                return Optional.of(cog.S("Error:JavaProxy:InvalidInterface:" + interfaceName));
            if (!isClassAllowed(interfaceName)) return Optional.of(cog.S("Error:JavaProxy:InterfaceNotAllowed"));

            try {
                var proxyInstance = Proxy.newProxyInstance(
                        this.getClass().getClassLoader(), // Use Cog's classloader
                        new Class<?>[]{iface},
                        createInvocationHandler(handlerExprTemplate) // Create handler that calls MeTTa
                );
                return Optional.of(j2m(proxyInstance)); // Wrap proxy in Is<>
            } catch (IllegalArgumentException e) {
                System.err.println("Error: Proxy creation failed: " + e);
                return Optional.of(cog.S("Error:JavaProxy:CreationFailed"));
            } catch (Exception e) {
                System.err.println("Error: Unexpected proxy error: " + e);
                return Optional.of(cog.S("Error:JavaProxy:UnexpectedError"));
            }
        }

        // Creates the InvocationHandler that bridges Java calls on the proxy to MeTTa evaluation.
        private InvocationHandler createInvocationHandler(Atom handlerExprTemplate) {
            // Ensure template is added to memory so it's accessible if needed (e.g., during substitution)
            cog.add(handlerExprTemplate);

            return (proxy, method, methodArgs) -> {
                // 1. Prepare MeTTa representations of the call details
                var proxyAtom = j2m(proxy); // Optional: Represent proxy instance itself
                var methodNameAtom = cog.S(method.getName());
                List<Atom> argAtoms = (methodArgs == null) ? List.of() : Arrays.stream(methodArgs).map(this::j2m).toList();
                Atom argsListAtom = cog.IS(argAtoms); // Represent args as Is<List<Atom>>

                // 2. Construct the MeTTa expression to evaluate by substituting into the template
                var bindings = new Bind(Map.of(
                        cog.V("proxy"), proxyAtom,
                        cog.V("method"), methodNameAtom, // Use simple symbol for name
                        cog.V("args"), argsListAtom     // Bind the Is<List<Atom>>
                ));
                var exprToEval = cog.unify.subst(handlerExprTemplate, bindings);

                // 3. Evaluate the expression using Cog's interpreter
                var mettaResultOpt = cog.evalBest(exprToEval);

                // 4. Convert the MeTTa result back to the expected Java type
                var javaResult = mettaResultOpt.map(this::m2j).orElse(null);

                // 5. Handle return type compatibility (void, primitives, object types)
                var returnType = method.getReturnType();
                if (returnType == void.class || returnType == Void.class) return null; // Void methods return null
                if (javaResult == null) {
                    if (returnType.isPrimitive())
                        throw new MettaProxyException("MeTTa handler returned null/Nil for primitive return type " + returnType.getName() + " in method " + methodNameAtom);
                    return null; // Return null for object types if MeTTa returned Nil
                }
                // If types are compatible, return result directly
                if (isTypeCompatible(returnType, javaResult.getClass())) return javaResult;
                // If not directly compatible, attempt conversion
                var convertedResult = attemptConversion(javaResult, returnType);
                if (convertedResult != null) return convertedResult;
                // Conversion failed or not possible
                throw new MettaProxyException("MeTTa handler result type (" + javaResult.getClass().getName() + ") incompatible with method return type (" + returnType.getName() + ") for method " + methodNameAtom);
            };
        }

        // Custom exception for MeTTa proxy handler issues
        static class MettaProxyException extends RuntimeException {
            MettaProxyException(String message) {
                super(message);
            }
        }
    }

    // --- Test Class for Java Field Access Demo ---
    // Must be static inner class or separate public class to be accessible via reflection `JavaNew`
    public static class TestClass {
        public static final String staticField = "StaticInitial"; // Public static field
        public String myField = "InitialValue"; // Public field for easy access in demo

        public TestClass() {
        } // Default constructor needed for JavaNew without args

        @Override
        public String toString() {
            return "TestClass[myField=" + myField + "]";
        }
    }

    /**
     * Interprets MeTTa expressions by applying rewrite rules (=) and executing Is functions.
     * The core evaluation strategy (`evalRecursive`) is a candidate for MeTTa-driven meta-interpretation.
     */
    public class Interp {
        private final Cog cog; // Access to Mem, Unify, Atom creation

        public Interp(Cog cog) {
            this.cog = cog;
        }

        // Convenience method for substitution
        public Atom subst(Atom atom, Bind bind) {
            return cog.unify.subst(atom, bind);
        }

        /**
         * Evaluates an atom, returning a list of possible results within maxDepth.
         */
        public List<Atom> eval(Atom atom, int maxDepth) {
            var results = evalRecursive(atom, maxDepth, new HashSet<>());
            // Return original atom if evaluation yields no results or only hits cycles/depth limit before finding a value
            return results.isEmpty() ? List.of(atom) : results.stream().distinct().limit(INTERPRETER_MAX_RESULTS).toList();
        }

        /**
         * Recursive evaluation core with depth limiting and cycle detection.
         * # MeTTa Integration Pathway: Meta-Interpretation
         * The current Java evaluation strategy follows a fixed order:
         * 1. Check Non-evaluatable Base Cases (Sym, Var, non-Fn Is)
         * 2. Expression evaluation:
         * a. Match specific equality rule `(= <expr> $result)`
         * b. Match general equality rule `(= <pattern> <template>)` unifying with `<expr>`
         * c. If head is Is<Fn>, evaluate args (applicative order) then execute function
         * d. Fallback: Evaluate children, reconstruct expression
         * 3. Direct Is<Fn> evaluation (evaluates to itself)
         * <p>
         * A MeTTa-driven approach would replace this fixed logic. Instead of hardcoded steps,
         * the interpreter would query meta-rules like:
         * `(= (eval-step (: $x Sym)) $x)` ; Symbols evaluate to themselves
         * `(= (eval-step $expr) (eval-by-specific-rule $expr))`
         * `(= (eval-step $expr) (eval-by-general-rule $expr)) :- (IsEmpty (eval-by-specific-rule $expr))` ; Try general if specific fails
         * `(= (eval-step ($fn $args...)) (apply-is-fn $fn (map eval-step $args...))) :- (: $fn Fn)`
         * `(= (eval-step ($head $tail...)) (Cons (eval-step $head) (map eval-step $tail...)))` ; Fallback constructor eval
         * This allows the evaluation process itself to be reflected upon and modified via MeTTa.
         */
        private List<Atom> evalRecursive(Atom atom, int depth, Set<String> visitedInPath) {
            // --- Termination Checks ---
            if (depth <= 0) return List.of(atom); // Max depth reached
            var atomId = atom.id(); // Use ID for cycle detection
            if (!visitedInPath.add(atomId)) return List.of(atom); // Cycle detected for this atom in this path

            List<Atom> combinedResults = new ArrayList<>();
            try {
                switch (atom) {
                    // --- Base Cases: Non-evaluatable atoms return themselves ---
                    case Sym s -> combinedResults.add(s);
                    case Var v -> combinedResults.add(v); // Variables only change via substitution, not evaluation
                    case Is<?> is when !is.isFn() -> combinedResults.add(is); // Non-function Is atoms are values

                    // --- Evaluate Expressions ---
                    case Expr expr -> {
                        var appliedRuleOrFn = false; // Track if a rewrite or execution happened

                        // Strategy 2a: Specific equality rule `(= <expr> $result)`
                        var resultVarSpecific = cog.V("evalResS" + depth); // Unique var name
                        Atom specificQuery = cog.E(SYM_EQ, expr, resultVarSpecific);
                        var specificMatches = cog.mem.query(specificQuery);
                        if (!specificMatches.isEmpty()) {
                            appliedRuleOrFn = true;
                            for (var match : specificMatches) {
                                match.bind.get(resultVarSpecific).ifPresent(target ->
                                        combinedResults.addAll(evalRecursive(target, depth - 1, new HashSet<>(visitedInPath)))); // Recurse on result
                                if (combinedResults.size() >= INTERPRETER_MAX_RESULTS) break;
                            }
                            // Optimization: If specific rule found, maybe don't try general rules? (Configurable by meta-rules)
                        }

                        // Strategy 2b: General equality rule `(= <pattern> <template>)` (only if specific failed or yielded few results)
                        if (!appliedRuleOrFn || combinedResults.size() < INTERPRETER_MAX_RESULTS / 2) {
                            var pVar = cog.V("p" + depth);
                            var tVar = cog.V("t" + depth);
                            Atom generalQuery = cog.E(SYM_EQ, pVar, tVar);
                            var ruleMatches = cog.mem.query(generalQuery); // Find all (= P T) rules
                            for (var ruleMatch : ruleMatches) {
                                var pattern = ruleMatch.bind.get(pVar).orElse(null);
                                var template = ruleMatch.bind.get(tVar).orElse(null);
                                // Skip invalid rules or self-matches already handled
                                if (pattern == null || template == null || pattern.equals(expr)) continue;
                                // Try to unify the current expression with the rule's pattern
                                cog.unify.unify(pattern, expr, Bind.EMPTY).ifPresent(exprBind -> {
                                    var result = subst(template, exprBind); // Apply bindings to template
                                    if (!result.equals(expr)) { // Avoid trivial self-loops from overly general rules
                                        combinedResults.addAll(evalRecursive(result, depth - 1, new HashSet<>(visitedInPath))); // Recurse on result
                                    }
                                });
                                if (!combinedResults.isEmpty())
                                    appliedRuleOrFn = true; // Mark rule applied if unification succeeded
                                if (combinedResults.size() >= INTERPRETER_MAX_RESULTS) break;
                            }
                        }

                        // Strategy 2c: Is Function execution (Applicative Order)
                        if ((!appliedRuleOrFn || combinedResults.isEmpty()) && expr.head() instanceof Is<?> isFn && isFn.isFn()) {
                            List<Atom> evaluatedArgs = new ArrayList<>();
                            var argEvalOk = true;
                            // Evaluate arguments first
                            for (var arg : expr.tail()) {
                                var argResults = evalRecursive(arg, depth - 1, new HashSet<>(visitedInPath));
                                // Strict: Requires single, non-error result per arg. Meta-rules could change this (e.g., allow multi-results, handle errors)
                                if (argResults.size() != 1 || argResults.getFirst().id().startsWith("Error:")) {
                                    argEvalOk = false;
                                    break;
                                }
                                evaluatedArgs.add(argResults.getFirst());
                            }
                            if (argEvalOk) { // If all args evaluated successfully
                                isFn.apply(evaluatedArgs).ifPresent(execResult -> { // Execute the Java function
                                    combinedResults.addAll(evalRecursive(execResult, depth - 1, new HashSet<>(visitedInPath))); // Evaluate the function's result
                                    // If function execution resulted in something, mark as applied
                                    if (!combinedResults.isEmpty() && (combinedResults.size() > 1 || !combinedResults.getFirst().equals(atom))) {
                                        // appliedRuleOrFn = true; // Causes issues with fallback eval
                                    }
                                });
                                // Mark function attempted/applied even if result was empty/non-recursive?
                                // Setting this prevents fallback eval if function returns empty Optional
                                appliedRuleOrFn = true;
                            }
                        }

                        // Strategy 2d: Fallback: Evaluate children and reconstruct (if no rules/exec applied OR they yielded no results)
                        // Acts like evaluating arguments of a constructor/data structure.
                        if (/*!appliedRuleOrFn ||*/ combinedResults.isEmpty()) { // Check combinedResults too
                            var childrenChanged = false;
                            List<Atom> evaluatedChildren = new ArrayList<>();
                            var currentHead = expr.head();
                            var currentTail = expr.tail();

                            // Evaluate Head (if exists)
                            var newHead = currentHead;
                            if (currentHead != null) {
                                var headResults = evalRecursive(currentHead, depth - 1, new HashSet<>(visitedInPath));
                                // Use single result if unambiguous, else keep original head
                                if (headResults.size() == 1 && !headResults.getFirst().equals(currentHead)) {
                                    newHead = headResults.getFirst();
                                    childrenChanged = true;
                                } else if (headResults.size() > 1) {
                                    // Ambiguous head evaluation, could return multiple results?
                                    // For now, keep original head if evaluation is ambiguous.
                                } else {
                                    newHead = headResults.stream().findFirst().orElse(currentHead); // Use result if exactly one, else original
                                }
                                // if (!newHead.equals(currentHead)) childrenChanged = true; // Check moved inside if
                            }
                            evaluatedChildren.add(newHead); // Add head (original or evaluated)

                            // Evaluate Tail
                            for (var child : currentTail) {
                                var childResults = evalRecursive(child, depth - 1, new HashSet<>(visitedInPath));
                                // Use single result if unambiguous, else keep original child
                                if (childResults.size() == 1 && !childResults.getFirst().equals(child)) {
                                    evaluatedChildren.add(childResults.getFirst());
                                    childrenChanged = true;
                                } else if (childResults.size() > 1) {
                                    // Ambiguous child evaluation - keep original for now
                                    evaluatedChildren.add(child);
                                } else {
                                    evaluatedChildren.add(childResults.stream().findFirst().orElse(child)); // Use result if exactly one, else original
                                }
                                //if (!newChild.equals(child)) childrenChanged = true; // Check moved inside if
                            }
                            // Return the reconstructed expression only if something changed, otherwise the original expression.
                            // Avoids infinite loops where eval(X) -> eval(X).
                            combinedResults.add(childrenChanged ? cog.E(evaluatedChildren) : expr);
                        }
                    }

                    // --- Evaluate Is functions called directly (uncommon) ---
                    // An Is<Fn> atom evaluated directly simply returns itself.
                    case Is<?> isFn -> combinedResults.add(isFn);
                }
            } finally {
                visitedInPath.remove(atomId); // Backtrack: remove current atom from path visited set
            }
            // Filter results to avoid returning the original atom if other results were found
            if (combinedResults.size() > 1) {
                combinedResults.removeIf(r -> r.equals(atom));
            }
            // If only result is the original atom (due to cycle or depth limit), keep it.
            return combinedResults;
        }
    }

    /**
     * Agent whose core behavior (perceive-select-execute-learn cycle) is driven by evaluating MeTTa expressions.
     */
    public class Agent {
        private final Cog cog; // Access to Cog's components (eval, add, S, E, etc.)
        private final Atom AGENT_STEP_EXPR; // Expression evaluated each cycle, e.g., (AgentStep Self)
        private final Atom SELF_SYM; // Agent's identifier in MeTTa space

        public Agent(Cog cog) {
            this.cog = cog;
            this.SELF_SYM = cog.S("Self");
            this.AGENT_STEP_EXPR = cog.E(cog.S("AgentStep"), SELF_SYM); // Canonical step expression
            // Ensure agent control symbols (used in CORE_METTA_RULES) are known & protected/boosted
            Stream.of("AgentStep", "AgentPerceive", "AgentSelectAction", "AgentExecute", "AgentLearn", "CheckGoal", "UpdateUtility", "Act", "Utility", "First", "FirstNumOrZero", "RandomChoice")
                    .map(cog::S) // Get symbol atoms
                    .filter(s -> !PROTECTED_SYMBOLS.contains(s.asSym().name)) // Avoid double-boosting core syntax if overlapping
                    .forEach(s -> {
                        cog.mem.updateTruth(s, Truth.TRUE);
                        cog.mem.boost(s, 0.8f);
                    }); // Boost agent-specific concepts
        }

        /**
         * Runs the agent's main control loop. The loop structure is Java, but each step's
         * internal logic (perceive, select, execute, learn) is determined by evaluating the
         * AGENT_STEP_EXPR using MeTTa rules defined in CORE_METTA_RULES.
         * # MeTTa Integration Pathway: Agent Control Flow
         * While the outer `for` loop is Java for efficiency and simplicity, the core
         * decision-making and state updates are delegated to `cog.eval(AGENT_STEP_EXPR)`.
         * A fully MeTTa-driven agent might replace the Java loop with a recursive MeTTa rule:
         * `(= (AgentRunLoop $agent $env $goal $cyclesLeft)
         * (If (== $cyclesLeft 0) Done ; Base case: max cycles
         * (If (IsGoalMet $agent $goal) Done ; Base case: goal met
         * (Seq (AgentStep $agent) ; Perform one step via existing rule
         * (AgentRunLoop $agent $env $goal (- $cyclesLeft 1))))))` ; Recurse
         * This requires `IsGoalMet`, `Seq` (sequential execution), numeric ops, and potentially passing $env.
         */
        public void run(Game g, Atom goalPattern, int maxCycles) {
            System.out.println("\n--- Agent Run Start (MeTTa Driven Steps) ---");
            initializeGoal(goalPattern); // Add goal representation to MeTTa space

            for (var cycle = 0; cycle < maxCycles && g.isRunning(); cycle++) {
                var time = cog.tick();
                System.out.printf("\n--- Agent Cycle %d (Time: %d) ---%n", cycle + 1, time);

                // --- Core Agent Logic Execution ---
                // Evaluate the main agent step expression. This triggers the MeTTa rules
                // defined in CORE_METTA_RULES for perceive, select, execute, learn.
                System.out.println("Agent: Evaluating step expr: " + AGENT_STEP_EXPR);
                var stepResults = cog.eval(AGENT_STEP_EXPR); // <<< MeTTa drives the internal logic >>>

                // Log the primary result of the step evaluation (if any informative result is returned)
                // The AgentStep rules might return True, the result of Learn, or Nil.
                stepResults.stream()
                        .filter(r -> !r.equals(AGENT_STEP_EXPR)) // Avoid logging if it just evaluates to itself (stuck)
                        .findFirst()
                        .ifPresentOrElse(
                                result -> System.out.println("Agent: Step evaluation result -> " + result),
                                () -> System.out.println("Agent: Step evaluation yielded no specific result or was stuck.")
                        );

                // Check if goal is achieved by evaluating the CheckGoal expression in MeTTa
                Atom goalCheckExpr = cog.E(cog.S("CheckGoal"), SELF_SYM, goalPattern);
                var goalMet = cog.evalBest(goalCheckExpr).filter(SYM_TRUE::equals).isPresent();

                if (goalMet) {
                    System.out.println("*** Agent: Goal Achieved (evaluated CheckGoal as True)! ***");
                    break; // Exit loop successfully
                }
                if (!g.isRunning()) { // Check if game stopped (e.g., game over)
                    System.out.println("--- Agent Run Halted (Game Stopped) ---");
                    break;
                }
                // Optional delay between cycles for observation
                // try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            } // End of agent cycle loop

            // --- Final Status Report ---
            var finalGoalCheck = cog.evalBest(cog.E(cog.S("CheckGoal"), SELF_SYM, goalPattern)).filter(SYM_TRUE::equals).isPresent();
            if (!g.isRunning() && !finalGoalCheck)
                System.out.println("--- Agent Run Finished (Game Stopped, Goal Not Met) ---");
            else if (!finalGoalCheck)
                System.out.println("--- Agent Run Finished (Max Cycles Reached, Goal Not Met) ---");
            else System.out.println("--- Agent Run Finished (Goal Met) ---");
        }

        /**
         * Adds the goal representation to memory with high priority and truth.
         */
        private void initializeGoal(Atom goalPattern) {
            // Represent goal fact: (Goal Self <pattern>)
            var goalAtom = cog.add(cog.E(cog.S("Goal"), SELF_SYM, goalPattern));
            cog.mem.updateTruth(goalAtom, Truth.TRUE); // Mark goal assertion as true
            cog.mem.boost(goalAtom, PRI_BOOST_ON_GOAL_FOCUS); // Boost the goal assertion itself
            // Also boost the pattern part of the goal for relevance in matching
            cog.mem.boost(goalPattern, PRI_BOOST_ON_GOAL_FOCUS * 0.8f);
            System.out.println("Agent: Goal initialized in MeTTa -> " + goalAtom + " | Value: " + cog.mem.value(goalAtom));
        }
    }

}
