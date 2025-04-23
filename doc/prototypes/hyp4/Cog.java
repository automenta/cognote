package dumb.hyp4;

import org.jetbrains.annotations.Nullable;

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

import static dumb.pln.Cog.unitize;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Optional.empty;

/**
 * Cog - Cognitive Logic Engine (Synthesized Hyperon-Inspired Iteration)
 * <p>
 * A unified cognitive architecture implementing a Hyperon/MeTTa-like symbolic system.
 * It integrates parsing, pattern matching, rule-based rewriting, probabilistic reasoning,
 * attention mechanisms, and agent interaction within a single, consolidated Java class structure.
 * <p>
 * ## Core Design:
 * - **Memory:** Central knowledge store holding immutable `Atom` objects (Symbols, Vars, Expressions, Is). Managed by {@link Mem}.
 * - **Unified Representation:** All concepts, relations, rules, procedures, types, states, goals are Atoms.
 * - **MeTTa Syntax:** Uses a LISP-like syntax for representing Atoms textually. Includes a {@link MettaParser} for conversion.
 * - **MeTTa Execution:** An {@link Interp} evaluates expressions by matching against equality (`=`) rules via {@link Unify}.
 * - **Homoiconicity:** Code (MeTTa rules/expressions) *is* data (Atoms), enabling reflection and self-modification.
 * - **Is Atoms:** {@link Is} bridges to Java code/data for I/O, math, environment interaction, JVM integration etc.
 * - **Metadata:** Immutable {@link Value} records (holding {@link Truth}, {@link Pri}, {@link Time}) associated with Atoms via {@link AtomicReference} for atomic updates.
 * - **Probabilistic & Priority Driven:** Truth values handle uncertainty; Pri values (STI/LTI) guide attention and forgetting.
 * - **Agent Framework:** Includes an {@link Agent} model demonstrating perception, reasoning (via evaluation), action, and learning. Key agent logic (control loop, action selection) is designed to be driven by MeTTa evaluation.
 * - **Modern Java:** Leverages Records, Sealed Interfaces, Streams, Concurrent Collections, AtomicReference, MethodHandles for robustness and conciseness.
 * <p>
 * ## Strategic MeTTa Integration Pathways:
 * This version identifies key areas where Java code can be strategically replaced or driven by MeTTa script
 * to enhance the system's self-representation, reflectivity, and integration capabilities, moving towards
 * a more complete MeTTa-based implementation.
 * <p>
 * 1.  **Interpreter Strategy (`Interp.evalRecursive`):** The core evaluation logic, currently hardcoded in Java, is the prime candidate. Defining the evaluation steps (rule matching order, function application strategy) via MeTTa rules enables meta-interpretation and runtime modification of system semantics. See comments in {@link Interp#evalRecursive}.
 * 2.  **JVM Integration (`Core.initIsFn` - Java* functions):** Implementing the placeholder `Java*` Is functions using Java Reflection, MethodHandles, and Proxies is crucial. This allows MeTTa scripts to directly interact with and control the JVM environment, enabling deep integration with existing Java code and libraries. See comments in {@link Core#initIsFn}.
 * 3.  **Agent Control Flow (`Agent.run`):** While individual agent steps are already MeTTa-driven, the main agent loop structure itself could be defined via MeTTa rules, further increasing the agent's self-definition. See comments in {@link Agent#run}.
 * 4.  **Configuration Management (Non-Budget):** Static configuration constants (like timeouts, limits, thresholds *excluding* budget/Pri mechanics) could be loaded from and managed via MeTTa atoms, allowing runtime introspection and modification. See comments near configuration constants.
 *
 * @version 5.0 - Strategic MeTTa Integration Pathways
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
            "Self", "Goal", "State", "Action", "Utility", "Implies", "Seq",
            // Core Is operations (implementations are Java, symbols are entry points)
            "match", "eval", "add-atom", "remove-atom", "get-value", "If", "+", "-", "*", "/", "==", ">", "<",
            "Concat", "Let", "IsEmpty", "IsNil", "RandomFloat",
            "&self", // Reference to the current space
            // Is operations for Agent interaction (bridge to environment)
            "AgentStep", "AgentPerceive", "AgentAvailableActions", "AgentExecute", "AgentLearn", "GetEnv", "CheckGoal", "UpdateUtility",
            // Is operations for JVM Integration (critical bridge)
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

        new Core(this).initialize(); // Initializes symbols, Is functions, and core MeTTa rules

        System.out.println("Cog (v5.0 MeTTa Integration Pathfinding) Initialized. Init Size: " + mem.size());
    }

    // --- Main Demonstration (Illustrates current capabilities and potential integration points) ---
    public static void main(String[] args) {
        var cog = new Cog();
        try {
            System.out.println("\n--- Cog Synthesized Test Suite (v5.0) ---");

            // --- [1] Parsing & Atom Basics (Foundation) ---
            printSectionHeader(1, "Parsing & Atom Basics");
            var red = cog.parse("Red");
            cog.add(red);
            cog.mem.updateTruth(red, new Truth(0.9, 5.0));
            System.out.println("Parsed Red: " + red + " | Value: " + cog.mem.value(red));

            // --- [2] Unification (Core mechanism, Java implementation) ---
            printSectionHeader(2, "Unification");
            var fact1 = cog.parse("(Likes Sam Pizza)");
            var pattern1 = cog.parse("(Likes Sam $w)");
            cog.add(fact1);
            testUnification(cog, "Simple Match", pattern1, fact1);

            // --- [3] MeTTa Evaluation - Core Rules (MeTTa rules + Java Is functions) ---
            printSectionHeader(3, "MeTTa Evaluation - Core Rules");
            evaluateAndPrint(cog, "Peano Add 2+1", cog.parse("(add (S (S Z)) (S Z))")); // Expect (S (S (S Z)))
            evaluateAndPrint(cog, "Arithmetic (2+3)*4", cog.parse("(* (+ 2.0 3.0) 4.0)")); // Expect is:Double:20.0
            evaluateAndPrint(cog, "If (5 > 3)", cog.parse("(If (> 5.0 3.0) FiveIsGreater ThreeIsGreater)")); // Expect FiveIsGreater

            // --- [4] Pattern Matching Query (`match` Is function) ---
            printSectionHeader(4, "Pattern Matching Query");
            cog.load("(Likes Sam Pizza)\n(Likes Dean Pizza)");
            evaluateAndPrint(cog, "Match Query", cog.parse("(match &self (Likes $p Pizza) $p)")); // Expect is:List:[Sam, Dean]

            // --- [5] Metaprogramming (Adding rules via `add-atom` Is function) ---
            printSectionHeader(5, "Metaprogramming");
            var ruleToAddAtom = cog.parse("(= (NewPred ConceptX) ResultX)");
            evaluateAndPrint(cog, "Add Rule Meta", cog.E(cog.S("add-atom"), ruleToAddAtom)); // Adds the rule
            evaluateAndPrint(cog, "Test New Rule", cog.parse("(NewPred ConceptX)")); // Expect [ResultX]

            // --- [6] Agent Simulation (Driven by MeTTa `AgentStep` evaluation) ---
            printSectionHeader(6, "Agent Simulation (MeTTa Driven)");
            // Agent behavior rules (AgentStep, AgentLearn, AgentSelectAction etc.) are defined in MeTTa
            // The Java Agent.run() loop orchestrates the *evaluation* of these rules.
            // See Agent.run() for comments on potentially moving the loop itself to MeTTa.
            cog.load("""
                    ; Define the main agent step - Perceive, Select, Execute, Learn sequence
                    (= (AgentStep $agent)
                       (AgentLearn $agent (AgentExecute $agent (AgentSelectAction $agent (AgentPerceive $agent)))))

                    ; How to learn: Update utility based on reward
                    ; Assumes AgentExecute returns (Act <percepts> <reward>)
                    ; Needs more sophisticated logic for state representation and TD learning
                    (= (AgentLearn $agent (Act $percepts $reward $prevState $action $nextState))
                       (UpdateUtility $agent $prevState $action $nextState $reward))

                    ; Simple utility update rule (placeholder for more complex learning)
                    (= (UpdateUtility $agent $prevS $act $nextS $reward)
                       (Let $utilPattern (= (Utility $act) $val)
                         (Let $currentUtil (match &self $utilPattern $val) ; Get current utility (needs processing if list)
                           (Let $newUtil (+ (FirstOrZero $currentUtil) (* 0.2 (- $reward (FirstOrZero $currentUtil)))) ; Simplified update, needs helper
                             (add-atom (= (Utility $act) $newUtil)))))) ; Add/update utility fact

                    ; Helper to get first number from list or 0 (assuming match returns list) - Needs Is function
                    ; (= (FirstOrZero ($num . $rest)) $num)
                    ; (= (FirstOrZero Nil) 0.0)
                    ; (= (FirstOrZero $other) 0.0) ; Fallback

                    ; Action selection strategy: Prefer utility, fallback to random
                    (= (AgentSelectAction $agent ($state . $actions)) ; Input: (State . Actions) list
                       (Let $bestAction (SelectBestUtility $actions) ; Needs implementation
                            (If (IsNil $bestAction) ; Check if best action is Nil
                                (SelectRandom $actions) ; Fallback if no utility found
                                $bestAction)))

                    ; Placeholder for finding action with highest utility (Requires list processing Is functions)
                     (= (SelectBestUtility $actions) Nil) ; Stub returning Nil

                    ; Placeholder for random selection (Requires list processing Is functions)
                     (= (SelectRandom ($action . $rest)) $action) ; Simplistic: just pick first if list not empty
                     (= (SelectRandom Nil) Nil) ; Handle empty actions

                    ; Goal Check Rule (Example)
                    (= (CheckGoal $agent $goalPattern)
                       (If (IsEmpty (match &self $goalPattern $goalPattern)) ; Query if goal pattern exists in memory
                           False ; Not found
                           True)) ; Found
                    """);

            var env = new SimpleGame(cog); // Uses the Cog instance
            var agentGoal = cog.parse("(State Self AtGoal)"); // Goal defined via MeTTa
            cog.runAgent(env, agentGoal, 10); // Run for a few steps (driven by AgentStep eval)

            System.out.println("\nQuerying learned utilities after MeTTa-driven run:");
            var utilQuery = cog.parse("(Utility $action)");
            var utilResults = cog.query(utilQuery); // Query the utility values directly
            printQueryResults(utilResults);

            // --- [7] JVM Integration (Conceptual Examples - Require Implementation) ---
            printSectionHeader(7, "JVM Integration (Conceptual)");
            // These rely on the Java* Is functions being implemented (see Core.initIsFn)
            // Example: Create a Java String using MeTTa
            // evaluateAndPrint(cog, "Java New String", cog.parse("(JavaNew java.lang.String \"Hello from MeTTa\")"));
            // Example: Call a static Java method
            // evaluateAndPrint(cog, "Java Static Math.max", cog.parse("(JavaStaticCall java.lang.Math max 5 10)"));
            // Example: Create an object, call its method
            // evaluateAndPrint(cog, "Java Instance Call",
            //     cog.parse("(Let (= $list (JavaNew java.util.ArrayList)) " +
            //               "     (JavaCall $list add \"item1\") " +
            //               "     (JavaCall $list size))")); // Expect Is<Integer:1>
            System.out.println("Note: JVM integration examples require implementation of Java* Is functions.");


            // --- [8] Forgetting & Maintenance (Java logic, potentially configurable via MeTTa) ---
            printSectionHeader(8, "Forgetting & Maintenance");
            System.out.println("Adding temporary atoms...");
            for (int i = 0; i < 50; i++) cog.add(cog.S("Temp_" + UUID.randomUUID().toString().substring(0, 6)));
            var sizeBefore = cog.mem.size();
            System.out.println("Memory size before maintenance: " + sizeBefore);
            // Forgetting thresholds (PRI_MIN_FORGET_THRESHOLD, FORGETTING_MAX_MEM_SIZE_TRIGGER etc.)
            // could potentially be read from MeTTa config atoms at the start of performMaintenance().
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
    private static void printSectionHeader(int sectionNum, String title) { System.out.printf("\n--- [%d] %s ---\n", sectionNum, title); }
    private static void testUnification(Cog cog, String name, Atom p, Atom i) { System.out.printf("Unify (%s): %s with %s -> %s%n", name, p, i, cog.unify.unify(p, i, Bind.EMPTY).map(Bind::toString).orElse("Failure")); }
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
    public Atom parse(String metta) { return parser.parse(metta); }
    public List<Atom> load(String mettaCode) { return parser.parseAll(mettaCode).stream().map(this::add).toList(); }
    public Atom S(String name) { return mem.sym(name); }
    public Var V(String name) { return mem.var(name); }
    public Expr E(Atom... children) { return mem.expr(List.of(children)); }
    public Expr E(List<Atom> children) { return mem.expr(children); }
    public <T> Is<T> IS(T value) { return mem.is(value); }
    public Is<Function<List<Atom>, Optional<Atom>>> IS(String name, Function<List<Atom>, Optional<Atom>> fn) { return mem.isFn(name, fn); }

    public long getLogicalTime() { return logicalTime.get(); }
    public long tick() { return logicalTime.incrementAndGet(); }
    public <A extends Atom> A add(A atom) { return mem.add(atom); }
    public Expr EQ(Atom premise, Atom conclusion) { return add(E(SYMBOL_EQ, premise, conclusion)); }
    public Expr TYPE(Atom instance, Atom type) { return add(E(SYMBOL_COLON, instance, type)); }
    public List<Atom> eval(Atom expr) { return interp.eval(expr, INTERPRETER_DEFAULT_MAX_DEPTH); }
    public List<Atom> eval(Atom expr, int maxDepth) { return interp.eval(expr, maxDepth); }
    public Optional<Atom> evalBest(Atom expr) { return eval(expr).stream().max(Comparator.comparingDouble(a -> mem.valueOrDefault(a).getWeightedTruth())); }
    public List<Answer> query(Atom pattern) { return mem.query(pattern); }

    /**
     * Runs the agent loop within a specified environment and goal.
     * The agent's behavior is primarily driven by MeTTa rules evaluated via `AgentStep`.
     */
    public void runAgent(Game env, Atom goal, int maxCycles) {
        this.environment = env; // Make environment accessible to Is functions like GetEnv, AgentPerceive etc.
        agent.run(env, goal, maxCycles); // Delegate to the agent instance
        this.environment = null; // Clear environment reference after run
    }

    public void shutdown() {
        scheduler.shutdown();
        try { if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) scheduler.shutdownNow(); }
        catch (InterruptedException e) { scheduler.shutdownNow(); Thread.currentThread().interrupt(); }
        System.out.println("Cog scheduler shut down.");
    }

    /** Performs periodic maintenance (decay and forgetting). Internal use by scheduler. */
    private void performMaintenance() { mem.decayAndForget(); }

    /** Retrieve the current game environment (used by Is functions). */
    public Optional<Game> env() { return Optional.ofNullable(environment); }

    // --- Core Data Structures (Records, Sealed Interfaces - Generally stable) ---
    public enum AtomType {SYM, VAR, EXPR, IS}
    public sealed interface Atom {
        String id(); AtomType type();
        default Sym asSym() { return (Sym) this; } default Var asVar() { return (Var) this; }
        default Expr asExpr() { return (Expr) this; } default Is<?> asIs() { return (Is<?>) this; }
        @Override boolean equals(Object other); @Override int hashCode(); @Override String toString();
    }
    public interface Game { // Environment interface
        List<Atom> perceive(Cog cog); List<Atom> actions(Cog cog, Atom currentState);
        Act exe(Cog cog, Atom action); boolean isRunning();
    }
    public record Sym(String name) implements Atom {
        private static final ConcurrentMap<String, Sym> SYMBOL_CACHE = new ConcurrentHashMap<>();
        public static Sym of(String name) { return SYMBOL_CACHE.computeIfAbsent(name, Sym::new); }
        @Override public String id() { return name; } @Override public AtomType type() { return AtomType.SYM; }
        @Override public String toString() { return name; } @Override public int hashCode() { return name.hashCode(); }
        @Override public boolean equals(Object o) { return this == o || (o instanceof Sym(String n1) && name.equals(n1)); }
    }
    public record Var(String name) implements Atom {
        public Var { if (name.startsWith(VAR_PREFIX)) throw new IllegalArgumentException("Var name excludes prefix"); }
        @Override public String id() { return VAR_PREFIX + name; } @Override public AtomType type() { return AtomType.VAR; }
        @Override public String toString() { return id(); } @Override public int hashCode() { return id().hashCode(); }
        @Override public boolean equals(Object o) { return this == o || (o instanceof Var v && id().equals(v.id())); }
    }
    public record Expr(String id, List<Atom> children) implements Atom {
        private static final ConcurrentMap<List<Atom>, String> idCache = new ConcurrentHashMap<>();
        public Expr(List<Atom> inputChildren) { this(idCache.computeIfAbsent(List.copyOf(inputChildren), Expr::computeIdInternal), List.copyOf(inputChildren)); }
        private static String computeIdInternal(List<Atom> childList) { return "(" + childList.stream().map(Atom::id).collect(Collectors.joining(" ")) + ")"; }
        @Override public String id() { return id; } @Override public AtomType type() { return AtomType.EXPR; }
        public @Nullable Atom head() { return children.isEmpty() ? null : children.getFirst(); }
        public List<Atom> tail() { return children.isEmpty() ? emptyList() : children.subList(1, children.size()); }
        @Override public String toString() { return id; } @Override public int hashCode() { return id.hashCode(); }
        @Override public boolean equals(Object o) { return this == o || (o instanceof Expr ea && id.equals(ea.id)); }
    }
    public record Is<T>(String id, @Nullable T value, @Nullable Function<List<Atom>, Optional<Atom>> fn) implements Atom {
        public Is(T value) { this(deriveId(value), value, null); }
        public Is(String id, T value) { this(id, value, null); }
        public Is(String name, Function<List<Atom>, Optional<Atom>> fn) { this(name, null, fn); }
        private static <T> String deriveId(T value) {
            if (value == null) return "is:null:null";
            var valStr = value.toString(); var typeName = value.getClass().getSimpleName();
            var valuePart = (value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Atom || valStr.length() < 30)
                    ? valStr : String.valueOf(valStr.hashCode());
            if (value instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Atom) {
                valuePart = "[" + list.stream().map(Object::toString).limit(3).collect(Collectors.joining(",")) + (list.size() > 3 ? ",..." : "") + "]";
                typeName = "List<Atom>";
            }
            return "is:" + typeName + ":" + valuePart;
        }
        @Override public AtomType type() { return AtomType.IS; }
        public boolean isFn() { return fn != null; }
        public Optional<Atom> apply(List<Atom> args) { return isFn() ? fn.apply(args) : empty(); }
        @Override public String toString() {
            if (isFn()) return "IsFn<" + id + ">";
            if (value instanceof String s) return "\"" + s + "\"";
            if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
            if (value instanceof Atom a) return a.toString(); // Show wrapped Atom directly
            if (value instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Atom) {
                return "Is<List:" + list.stream().map(Object::toString).limit(5).collect(Collectors.joining(",", "[", list.size() > 5 ? ",...]" : "]")) + ">";
            }
            var valStr = String.valueOf(value);
            return "Is<" + (value != null ? value.getClass().getSimpleName() : "null") + ":"
                    + (valStr.length() > 20 ? valStr.substring(0, 17) + "..." : valStr) + ">";
        }
        @Override public int hashCode() { return id.hashCode(); }
        @Override public boolean equals(Object o) { return this == o || (o instanceof Is<?> ga && id.equals(ga.id)); }
    }

    // --- Metadata Records (Java implementation of Pri/Truth logic likely retained for performance) ---
    public record Value(Truth truth, Pri pri, Time access) {
        public static final Value DEFAULT = new Value(Truth.UNKNOWN, Pri.DEFAULT, Time.DEFAULT);
        public Value withTruth(Truth nt) { return new Value(nt, pri, access); }
        public Value withPri(Pri np) { return new Value(truth, np, access); }
        public Value withTime(Time na) { return new Value(truth, pri, na); }
        public Value updateTime(long now) { return new Value(truth, pri, new Time(now)); }
        public Value boost(double amount, long now) { return withPri(pri.boost(amount)).updateTime(now); }
        public Value decay(long now) { return withPri(pri.decay()).updateTime(now); } // Update time on decay too
        public double getCurrentPri(long now) {
            double timeFactor = Math.exp(-Math.max(0, now - access.time()) / (FORGETTING_CHECK_INTERVAL_MS * 3.0));
            return pri.getCurrent(timeFactor) * truth.confidence();
        }
        public double getWeightedTruth() { return truth.confidence() * truth.strength; }
        @Override public String toString() { return truth + " " + pri + " " + access; }
    }
    public record Truth(double strength, double count) {
        public static final Truth TRUE = new Truth(1, 10.0); public static final Truth FALSE = new Truth(0, 10.0);
        public static final Truth UNKNOWN = new Truth(0.5, 0.1);
        public Truth { strength = unitize(strength); count = Math.max(0, count); }
        public double confidence() { return count / (count + TRUTH_DEFAULT_SENSITIVITY); }
        public Truth merge(Truth other) {
            if (other == null || other.count == 0) return this; if (this.count == 0) return other;
            var totalCount = this.count + other.count;
            var mergedStrength = (this.strength * this.count + other.strength * other.count) / totalCount;
            return new Truth(mergedStrength, totalCount);
        }
        @Override public String toString() { return String.format("%%%.3f,%.2f%%", strength, count); }
    }
    public record Pri(double sti, double lti) { // Pri mechanics kept in Java for performance (per guidelines)
        public static final Pri DEFAULT = new Pri(PRI_INITIAL_STI, PRI_INITIAL_STI * PRI_INITIAL_LTI_FACTOR);
        public Pri { sti = unitize(sti); lti = unitize(lti); }
        public Pri decay() {
            var decayedSti = sti * (1 - PRI_STI_DECAY_RATE);
            var ltiGain = sti * PRI_STI_DECAY_RATE * PRI_STI_TO_LTI_RATE;
            var decayedLti = lti * (1 - PRI_LTI_DECAY_RATE) + ltiGain;
            return new Pri(decayedSti, decayedLti);
        }
        public Pri boost(double amount) {
            if (amount <= 0) return this;
            var boostedSti = unitize(sti + amount);
            var ltiBoostFactor = PRI_STI_TO_LTI_RATE * Math.abs(amount);
            var boostedLti = unitize(lti + (boostedSti - sti) * ltiBoostFactor);
            return new Pri(boostedSti, boostedLti);
        }
        public double getCurrent(double recencyFactor) { return sti * recencyFactor * 0.6 + lti * 0.4; }
        @Override public String toString() { return String.format("$%.3f,%.3f", sti, lti); }
    }
    public record Time(long time) {
        public static final Time DEFAULT = new Time(0L); @Override public String toString() { return "@" + time; }
    }

    // --- Core Engine Components ---

    /** Manages Atom storage, indexing, metadata, and forgetting. Java implementation is performance-critical. */
    public static class Mem {
        private final ConcurrentMap<String, Atom> atomsById = new ConcurrentHashMap<>(1024);
        private final ConcurrentMap<Atom, AtomicReference<Value>> storage = new ConcurrentHashMap<>(1024);
        private final ConcurrentMap<Atom, ConcurrentSkipListSet<String>> indexByHead = new ConcurrentHashMap<>();
        private final Supplier<Long> timeSource;
        public Mem(Supplier<Long> timeSource) { this.timeSource = timeSource; }

        @SuppressWarnings("unchecked") public <A extends Atom> A add(A atom) {
            var canonicalAtom = (A) atomsById.computeIfAbsent(atom.id(), id -> atom);
            long now = timeSource.get();
            var valueRef = storage.computeIfAbsent(canonicalAtom, k -> {
                var initVal = Value.DEFAULT.withPri(new Pri(PRI_INITIAL_STI, PRI_INITIAL_STI * PRI_INITIAL_LTI_FACTOR)).updateTime(now);
                updateIndices(k); return new AtomicReference<>(initVal);
            });
            valueRef.updateAndGet(v -> v.boost(PRI_BOOST_ON_ACCESS * 0.1, now)); // Small boost on every access/add
            checkMemoryAndTriggerForgetting();
            return canonicalAtom;
        }
        public Optional<Atom> getAtom(String id) { return Optional.ofNullable(atomsById.get(id)).map(this::boostAndGet); }
        public Optional<Atom> getAtom(Atom atom) { return Optional.ofNullable(atomsById.get(atom.id())).map(this::boostAndGet); }
        private Atom boostAndGet(Atom atom) { updateValue(atom, v -> v.boost(PRI_BOOST_ON_ACCESS, timeSource.get())); return atom; }
        public Optional<Value> value(Atom atom) { return Optional.ofNullable(atomsById.get(atom.id())).flatMap(key -> Optional.ofNullable(storage.get(key))).map(AtomicReference::get); }
        public Value valueOrDefault(Atom atom) { return value(atom).orElse(Value.DEFAULT); }
        public void updateValue(Atom atom, Function<Value, Value> updater) {
            var canonicalAtom = atomsById.get(atom.id()); if (canonicalAtom == null) return;
            var valueRef = storage.get(canonicalAtom);
            if (valueRef != null) {
                long now = timeSource.get();
                valueRef.updateAndGet(current -> {
                    var updated = updater.apply(current).updateTime(now); // Always update time on modification
                    var confDiff = updated.truth.confidence() - current.truth.confidence();
                    var boost = (confDiff > TRUTH_REVISION_CONFIDENCE_THRESHOLD) ? PRI_BOOST_ON_REVISION_MAX * updated.truth.confidence() : 0.0;
                    return boost > 0 ? updated.boost(boost, now) : updated; // Apply revision boost if needed
                });
            }
        }
        public void updateTruth(Atom atom, Truth newTruth) { updateValue(atom, v -> v.withTruth(v.truth.merge(newTruth))); }
        public void boost(Atom atom, double amount) { updateValue(atom, v -> v.boost(amount, timeSource.get())); }
        public Atom sym(String name) { return add(Sym.of(name)); }
        public Var var(String name) { return add(new Var(name)); }
        public Expr expr(List<Atom> children) { return add(new Expr(children)); }
        public Expr expr(Atom... children) { return add(new Expr(List.of(children))); }
        public <T> Is<T> is(T value) { return add(new Is<>(value)); }
        public <T> Is<T> is(String id, T value) { return add(new Is<>(id, value)); }
        public Is<Function<List<Atom>, Optional<Atom>>> isFn(String name, Function<List<Atom>, Optional<Atom>> fn) {
            Is<Function<List<Atom>, Optional<Atom>>> i = new Is<>(name, fn);
            add(i);
            return i;
        }

        /** Performs pattern matching query. Uses Java Streams and Unify for performance. */
        public List<Answer> query(Atom pattern) {
            var queryPattern = add(pattern); boost(queryPattern, PRI_BOOST_ON_ACCESS * 0.2);
            Stream<Atom> candidateStream;
            if (queryPattern instanceof Expr pExpr && pExpr.head() != null) {
                candidateStream = indexByHead.getOrDefault(pExpr.head(), new ConcurrentSkipListSet<>()).stream().map(this::getAtom).flatMap(Optional::stream);
                candidateStream = Stream.concat(candidateStream, Stream.of(queryPattern).filter(storage::containsKey)); // Also check direct match
            } else if (queryPattern instanceof Var) { candidateStream = storage.keySet().stream(); // Matches all, potentially slow
            } else { candidateStream = Stream.of(queryPattern).filter(storage::containsKey); } // Sym or Is direct check

            List<Answer> results = new ArrayList<>(); var unification = new Unify(this);
            var checkCount = 0; var maxChecks = Math.min(5000 + size() / 10, 15000);
            var it = candidateStream.iterator();
            while (it.hasNext() && results.size() < INTERPRETER_MAX_RESULTS && checkCount < maxChecks) {
                var candidate = it.next(); checkCount++;
                if (valueOrDefault(candidate).truth.confidence() < TRUTH_MIN_CONFIDENCE_MATCH) continue; // Confidence filter
                unification.unify(queryPattern, candidate, Bind.EMPTY).ifPresent(bind -> {
                    boost(candidate, PRI_BOOST_ON_ACCESS); results.add(new Answer(candidate, bind));
                });
            }
            results.sort(Comparator.comparingDouble((Answer ans) -> valueOrDefault(ans.resultAtom()).getWeightedTruth()).reversed());
            return results.stream().limit(INTERPRETER_MAX_RESULTS).toList();
        }

        /** Decays Pri and removes low-Pri atoms. Core logic in Java for performance. Configurable thresholds could be MeTTa-loaded. */
        synchronized void decayAndForget() {
            final long now = timeSource.get(); var initialSize = storage.size(); if (initialSize == 0) return;
            // Thresholds could be fetched here, e.g.,
            // double forgetThreshold = cog.getConfigDouble("pri-min-forget-threshold", PRI_MIN_FORGET_THRESHOLD);
            // int maxMemTrigger = cog.getConfigInt("forgetting-max-mem-trigger", FORGETTING_MAX_MEM_SIZE_TRIGGER); etc.
            final double currentForgetThreshold = PRI_MIN_FORGET_THRESHOLD; // Using static final for now
            final int currentMaxMemTrigger = FORGETTING_MAX_MEM_SIZE_TRIGGER;
            final int currentTargetFactor = FORGETTING_TARGET_MEM_SIZE_FACTOR;

            List<Atom> candidatesForRemoval = new ArrayList<>(); var decayCount = 0;
            for (var entry : storage.entrySet()) {
                var atom = entry.getKey(); var valueRef = entry.getValue();
                var isProtected = (atom instanceof Sym s && PROTECTED_SYMBOLS.contains(s.name())) || atom instanceof Var;
                var decayedValue = valueRef.updateAndGet(v -> v.decay(now)); decayCount++;
                if (!isProtected && decayedValue.getCurrentPri(now) < currentForgetThreshold) {
                    candidatesForRemoval.add(atom);
                }
            }
            var removedCount = 0;
            var targetSize = currentMaxMemTrigger * currentTargetFactor / 100;
            var memoryPressure = initialSize > currentMaxMemTrigger;
            var significantLowPri = candidatesForRemoval.size() > initialSize * 0.05;
            if (!candidatesForRemoval.isEmpty() && (memoryPressure || significantLowPri || initialSize > targetSize)) {
                candidatesForRemoval.sort(Comparator.comparingDouble(atom -> valueOrDefault(atom).getCurrentPri(now)));
                var removalTargetCount = memoryPressure ? Math.max(0, initialSize - targetSize) : candidatesForRemoval.size();
                var actuallyRemoved = 0;
                for (var atomToRemove : candidatesForRemoval) {
                    if (actuallyRemoved >= removalTargetCount) break;
                    if (valueOrDefault(atomToRemove).getCurrentPri(now) < currentForgetThreshold) { // Re-check threshold
                        if (removeAtomInternal(atomToRemove)) actuallyRemoved++;
                    }
                } removedCount = actuallyRemoved;
            }
            if (removedCount > 0 || decayCount > 0 && initialSize > 10) {
                System.out.printf("Memory Maintenance: Decayed %d atoms. Removed %d low-pri atoms (Threshold=%.3f). Size %d -> %d.%n",
                        decayCount, removedCount, currentForgetThreshold, initialSize, storage.size());
            }
        }
        boolean removeAtomInternal(Atom atom) {
            if (storage.remove(atom) != null) { atomsById.remove(atom.id()); removeIndices(atom); return true; } return false;
        }
        private void checkMemoryAndTriggerForgetting() { if (storage.size() > FORGETTING_MAX_MEM_SIZE_TRIGGER) CompletableFuture.runAsync(this::decayAndForget); }
        private void updateIndices(Atom atom) { if (atom instanceof Expr e && e.head() != null) indexByHead.computeIfAbsent(e.head(), h -> new ConcurrentSkipListSet<>()).add(atom.id()); }
        private void removeIndices(Atom atom) { if (atom instanceof Expr e && e.head() != null) indexByHead.computeIfPresent(e.head(), (k, v) -> { v.remove(atom.id()); return v.isEmpty() ? null : v; }); }
        public int size() { return storage.size(); }
    }

    /** Represents unification bindings. Kept as Java record. */
    public record Bind(Map<Var, Atom> map) {
        public static final Bind EMPTY = new Bind(emptyMap()); public Bind { map = Map.copyOf(map); }
        public boolean isEmpty() { return map.isEmpty(); } public Optional<Atom> get(Var var) { return Optional.ofNullable(map.get(var)); }
        public Optional<Atom> getRecursive(Var var) {
            var current = map.get(var); if (current == null) return empty(); Set<Var> visited = new HashSet<>(); visited.add(var);
            while (current instanceof Var v) { if (!visited.add(v)) return empty(); var next = map.get(v); if (next == null) return Optional.of(v); current = next; }
            return Optional.of(current);
        }
        @Override public String toString() { return map.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", ", "{", "}")); }
    }

    /** Represents a query answer. Kept as Java record. */
    public record Answer(Atom resultAtom, Bind bind) {}

    /** Performs unification. Core algorithm kept in Java for performance. */
    public static class Unify {
        private final Mem space; public Unify(Mem space) { this.space = space; }
        /** Unification logic (iterative stack-based approach). */
        public Optional<Bind> unify(Atom pattern, Atom instance, Bind initialBind) {
            Deque<Pair<Atom, Atom>> stack = new ArrayDeque<>(); stack.push(new Pair<>(pattern, instance)); var currentBinds = initialBind;
            while (!stack.isEmpty()) {
                var task = stack.pop(); var p = subst(task.a, currentBinds); var i = subst(task.b, currentBinds);
                if (p.equals(i)) continue; // Match
                if (p instanceof Var pVar) { // Pattern var
                    if (containsVar(i, pVar)) return empty(); // Occurs check failure
                    currentBinds = mergeBind(currentBinds, pVar, i); if (currentBinds == null) return empty(); continue;
                }
                if (i instanceof Var iVar) { // Instance var (less common in typical KB queries)
                    if (containsVar(p, iVar)) return empty(); // Occurs check failure
                    currentBinds = mergeBind(currentBinds, iVar, p); if (currentBinds == null) return empty(); continue;
                }
                if (p instanceof Expr pExpr && i instanceof Expr iExpr) { // Both exprs
                    var pChildren = pExpr.children(); var iChildren = iExpr.children(); var pn = pChildren.size(); if (pn != iChildren.size()) return empty(); // Arity mismatch
                    for (var j = pn - 1; j >= 0; j--) stack.push(new Pair<>(pChildren.get(j), iChildren.get(j))); // Push children pairs LIFO
                    continue;
                }
                return empty(); // Mismatch (Sym vs Expr, different Syms, etc.)
            } return Optional.of(currentBinds); // Success
        }
        /** Applies bindings recursively. */
        public Atom subst(Atom atom, Bind bind) {
            if (bind.isEmpty() || !(atom instanceof Var || atom instanceof Expr)) return atom;
            return switch (atom) {
                case Var var -> bind.getRecursive(var).map(val -> subst(val, bind)).orElse(var);
                case Expr expr -> {
                    var changed = false; List<Atom> newChildren = new ArrayList<>(expr.children().size());
                    for (var child : expr.children()) { var substChild = subst(child, bind); if (child != substChild) changed = true; newChildren.add(substChild); }
                    yield changed ? new Expr(newChildren) : expr; // Reuse original Expr if no change
                } default -> atom; // Sym, Is are constants w.r.t. substitution
            };
        }
        /** Occurs check helper. */
        private boolean containsVar(Atom expr, Var var) {
            return switch (expr) { case Var v -> v.equals(var); case Expr e -> e.children().stream().anyMatch(c -> containsVar(c, var)); default -> false; };
        }
        /** Merges a new binding, handling existing bindings via unification. */
        private @Nullable Bind mergeBind(Bind current, Var var, Atom value) {
            var existingBindOpt = current.getRecursive(var);
            if (existingBindOpt.isPresent()) { // Var already bound?
                return unify(existingBindOpt.get(), value, current).orElse(null); // Unify existing with new value, return null on conflict
            } else { // New binding
                var m = new HashMap<>(current.map()); m.put(var, value); return new Bind(m);
            }
        }
    }

    /**
     * Interprets MeTTa expressions by applying rewrite rules and executing Is functions.
     * The core evaluation strategy here (`evalRecursive`) is a major candidate for being
     * defined and driven by MeTTa itself for meta-interpretation capabilities.
     */
    public class Interp {
        private final Cog cog; public Interp(Cog cog) { this.cog = cog; }
        public Atom subst(Atom atom, Bind bind) { return cog.unify.subst(atom, bind); }

        /** Evaluates an atom using the default max depth. */
        public List<Atom> eval(Atom atom, int maxDepth) {
            var results = evalRecursive(atom, maxDepth, new HashSet<>());
            // Return original atom if evaluation yields no results (e.g., only cycles or failed matches)
            return results.isEmpty() ? List.of(atom) : results.stream().distinct().limit(INTERPRETER_MAX_RESULTS).toList();
        }

        /**
         * Recursive evaluation core logic with depth limiting and cycle detection.
         *
         * # MeTTa Integration Pathway: Meta-Interpretation
         *
         * The current Java implementation follows a fixed evaluation strategy:
         * 1. Check for specific equality rule `(= <expr> $result)`.
         * 2. Check for general equality rule `(= <pattern> <template>)`.
         * 3. Check if head is an executable `Is` function and evaluate args applicatively.
         * 4. If none apply, evaluate children and reconstruct expression.
         * 5. Fallback: Atom evaluates to itself.
         *
         * This strategy could be defined declaratively in MeTTa. Instead of Java `if/switch`,
         * this method could query the MeTTa space for rules governing evaluation itself.
         *
         * Example Meta-Rules (Conceptual):
         * ```metta
         * ; Define evaluation order preference
         * (: EvalStepOrder Type)
         * (: SpecificRuleMatch EvalStepOrder)
         * (: GeneralRuleMatch EvalStepOrder)
         * (: GroundedExec EvalStepOrder)
         * (: EvalChildren EvalStepOrder)
         *
         * ; Rule to try specific match first
         * (= (Interpret $expr $depth) (TrySpecificMatch $expr $depth))
         *
         * ; If specific match yields result, use it (non-deterministic choice needed for multiple results)
         * (= (TrySpecificMatch $expr $depth) $result) :-
         *    (= $expr $result), (Not (= $result $expr)) ; Found a non-trivial specific rule
         *
         * ; If specific match fails or yields no result, try general match (needs control flow)
         * (= (TrySpecificMatch $expr $depth) (TryGeneralMatch $expr $depth)) :-
         *    (IsEmpty (match &self (= $expr $any) $any)) ; Check if NO specific rule exists
         *
         * ; Rule to handle grounded execution
         * (= (Interpret ($fn . $args) $depth) (ExecGrounded $fn $args $depth)) :-
         *    (isa $fn GroundedFn) ; Check if $fn is a grounded function type
         *
         * ; ... and so on ...
         * ```
         *
         * Implementing this would involve:
         * - Representing the evaluation state (expression, depth) as MeTTa atoms.
         * - Querying these meta-rules within `evalRecursive`.
         * - Handling the results of meta-queries to drive the next step (which might be calling Java helper functions for actual unification or Is execution).
         *
         * Benefits: Enables runtime modification of evaluation strategy, different evaluation orders (normal order), reflection on the interpretation process itself.
         * Challenges: Performance overhead of meta-interpretation, careful design of meta-rules and control flow. A hybrid approach (Java core operations guided by MeTTa strategy rules) might be practical.
         */
        private List<Atom> evalRecursive(Atom atom, int depth, Set<String> visitedInPath) {
            if (depth <= 0 || !visitedInPath.add(atom.id())) return List.of(atom); // Base case: Depth or Cycle

            List<Atom> combinedResults = new ArrayList<>();
            try {
                switch (atom) {
                    // Non-evaluatable base cases
                    case Sym s -> combinedResults.add(s);
                    case Var v -> combinedResults.add(v);
                    case Is<?> ga when !ga.isFn() -> combinedResults.add(ga); // Non-fn Is are values

                    // Evaluate Expressions
                    case Expr expr -> {
                        // --- Strategy 1: Specific equality rule `(= <expr> $result)` ---
                        var resultVar = V("evalRes" + depth); Atom specificQuery = E(SYMBOL_EQ, expr, resultVar);
                        var specificMatches = cog.mem.query(specificQuery);
                        if (!specificMatches.isEmpty()) {
                            for (var match : specificMatches) {
                                match.bind.get(resultVar).ifPresent(target ->
                                        combinedResults.addAll(evalRecursive(target, depth - 1, new HashSet<>(visitedInPath))));
                                if (combinedResults.size() >= INTERPRETER_MAX_RESULTS) break;
                            }
                            // Potential Exit Point: If specific rules yield results, MeTTa could stop here.
                            // Current impl continues non-deterministically. Meta-rules could control this.
                        }

                        // --- Strategy 2: General equality rule `(= <pattern> <template>)` ---
                        if (combinedResults.size() < INTERPRETER_MAX_RESULTS) {
                            var pVar = V("p" + depth); var tVar = V("t" + depth); Atom generalQuery = E(SYMBOL_EQ, pVar, tVar);
                            var ruleMatches = cog.mem.query(generalQuery);
                            for (var ruleMatch : ruleMatches) {
                                var pattern = ruleMatch.bind.get(pVar).orElse(null); var template = ruleMatch.bind.get(tVar).orElse(null);
                                if (pattern == null || template == null || pattern.equals(expr)) continue; // Skip invalid or self-rules
                                var exprBindOpt = cog.unify.unify(pattern, expr, Bind.EMPTY);
                                if (exprBindOpt.isPresent()) {
                                    var result = subst(template, exprBindOpt.get());
                                    combinedResults.addAll(evalRecursive(result, depth - 1, new HashSet<>(visitedInPath)));
                                    if (combinedResults.size() >= INTERPRETER_MAX_RESULTS) break;
                                }
                            }
                        }

                        // --- Strategy 3: Is Function execution (Applicative Order) ---
                        if (combinedResults.size() < INTERPRETER_MAX_RESULTS && expr.head() instanceof Is<?> ga && ga.isFn()) {
                            List<Atom> evaluatedArgs = new ArrayList<>(); var argEvalOk = true;
                            for (var arg : expr.tail()) { // Evaluate arguments first
                                var argResults = evalRecursive(arg, depth - 1, new HashSet<>(visitedInPath));
                                // Strict applicative order: Requires single result for each arg.
                                // Meta-rules could implement normal order (pass unevaluated args) or other strategies.
                                if (argResults.size() != 1) { argEvalOk = false; break; }
                                evaluatedArgs.add(argResults.getFirst());
                            }
                            if (argEvalOk) { // If all args evaluated successfully
                                ga.apply(evaluatedArgs).ifPresent(execResult -> // Execute Java function
                                        combinedResults.addAll(evalRecursive(execResult, depth - 1, new HashSet<>(visitedInPath)))); // Evaluate result
                            }
                        }

                        // --- Strategy 4: Evaluate children and reconstruct (if no rules/exec applied) ---
                        // This happens only if strategies 1-3 yielded no results.
                        if (combinedResults.isEmpty()) {
                            var childrenChanged = false; List<Atom> evaluatedChildren = new ArrayList<>();
                            // Evaluate Head (if exists)
                            if (expr.head() != null) {
                                var headResults = evalRecursive(expr.head(), depth - 1, new HashSet<>(visitedInPath));
                                var newHead = (headResults.size() == 1) ? headResults.getFirst() : expr.head(); // Keep original on eval fail/multi-result
                                evaluatedChildren.add(newHead); if (!newHead.equals(expr.head())) childrenChanged = true;
                            }
                            // Evaluate Tail
                            for (var child : expr.tail()) {
                                var childResults = evalRecursive(child, depth - 1, new HashSet<>(visitedInPath));
                                var newChild = (childResults.size() == 1) ? childResults.getFirst() : child;
                                evaluatedChildren.add(newChild); if (!newChild.equals(child)) childrenChanged = true;
                            }
                            // If any child evaluation changed the atom, return the new expression, else the original.
                            combinedResults.add(childrenChanged ? cog.E(evaluatedChildren) : expr);
                        }

                        // --- Fallback --- (Included implicitly by Strategy 4 returning original expr if no change)
                        // If combinedResults is STILL empty after all strategies, it means the original expression
                        // is the result (e.g., a constructor like (Cons A B) where A, B don't evaluate further).
                        // Added explicitly for clarity, though strategy 4 handles this.
                        if (combinedResults.isEmpty()) combinedResults.add(expr);
                    }
                    // Is functions evaluate to themselves if called directly (not as head of expr)
                    case Is<?> ga -> combinedResults.add(ga);
                }
            } finally { visitedInPath.remove(atom.id()); } // Backtrack from current path visit
            return combinedResults;
        }
    }

    /** Parses MeTTa text into Atoms. Kept as Java implementation for performance. */
    private static class MettaParser {
        private final Cog cog; MettaParser(Cog cog) { this.cog = cog; }
        private Atom parseSymbolOrIs(String text) {
            return switch (text) { case "True" -> SYMBOL_TRUE; case "False" -> SYMBOL_FALSE; case "Nil" -> SYMBOL_NIL; default -> cog.S(text); };
        }
        private String unescapeString(String s) { return s.replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\"); }
        private List<Token> tokenize(String text) {
            List<Token> tokens = new ArrayList<>(); var line = 1; var col = 1; var i = 0;
            while (i < text.length()) {
                var c = text.charAt(i); var startCol = col;
                if (Character.isWhitespace(c)) { if (c == '\n') { line++; col = 1; } else col++; i++; continue; } // Whitespace
                switch (c) {
                    case '(' -> {
                        tokens.add(new Token(TokenType.LPAREN, "(", line, startCol));
                        i++;
                        col++;
                        continue;
                    }
                    case ')' -> {
                        tokens.add(new Token(TokenType.RPAREN, ")", line, startCol));
                        i++;
                        col++;
                        continue;
                    }
                    case ';' -> {
                        while (i < text.length() && text.charAt(i) != '\n') i++;
                        if (i < text.length() && text.charAt(i) == '\n') {
                            line++;
                            col = 1;
                            i++;
                        } else col = 1;
                        continue;
                    }
                    case '"' -> {
                        var start = i;
                        i++;
                        col++;
                        var sb = new StringBuilder();
                        var escaped = false;
                        while (i < text.length()) {
                            var nc = text.charAt(i);
                            if (nc == '"' && !escaped) {
                                i++;
                                col++;
                                tokens.add(new Token(TokenType.STRING, text.substring(start, i), line, startCol));
                                break;
                            }
                            if (nc == '\n') throw new MettaParseException("Unterminated string at line " + line);
                            sb.append(nc);
                            escaped = (nc == '\\' && !escaped);
                            i++;
                            col++;
                        }
                        if (i == text.length() && (tokens.isEmpty() || tokens.getLast().type != TokenType.STRING))
                            throw new MettaParseException("Unterminated string at EOF");
                        continue;
                    }
                }
                if (c == VAR_PREFIX.charAt(0)) { // Variable
                    var start = i; i++; col++; while (i < text.length() && !Character.isWhitespace(text.charAt(i)) && text.charAt(i) != '(' && text.charAt(i) != ')') { i++; col++; }
                    var varName = text.substring(start, i); if (varName.length() == 1) throw new MettaParseException("Invalid var name '$' at line " + line); tokens.add(new Token(TokenType.VAR, varName, line, startCol)); continue;
                }
                // Symbol or Number
                var start = i; var maybeNumber = Character.isDigit(c) || (c == '-' && i + 1 < text.length() && Character.isDigit(text.charAt(i + 1))); var hasDot = false; var hasExp = false;
                while (i < text.length() && !Character.isWhitespace(text.charAt(i)) && text.charAt(i) != '(' && text.charAt(i) != ')' && text.charAt(i) != ';') {
                    var nc = text.charAt(i);
                    if (nc == '.') { if (hasDot || hasExp) maybeNumber = false; else hasDot = true; }
                    else if (nc == 'e' || nc == 'E') { if (hasExp) maybeNumber = false; else hasExp = true; }
                    else if (nc == '+' || nc == '-') { if (i != start && !(hasExp && (text.charAt(i - 1) == 'e' || text.charAt(i - 1) == 'E'))) maybeNumber = false; }
                    else if (!Character.isDigit(nc)) maybeNumber = false;
                    i++; col++;
                } var value = text.substring(start, i);
                try { if (maybeNumber) { Double.parseDouble(value); tokens.add(new Token(TokenType.NUMBER, value, line, startCol)); } else tokens.add(new Token(TokenType.SYMBOL, value, line, startCol)); }
                catch (NumberFormatException e) { tokens.add(new Token(TokenType.SYMBOL, value, line, startCol)); } // Treat as symbol if number parse fails
            } return tokens;
        }
        private Atom parseAtomFromTokens(PeekableIterator<Token> it) {
            if (!it.hasNext()) throw new MettaParseException("Unexpected EOF"); var token = it.next();
            return switch (token.type) {
                case LPAREN -> parseExprFromTokens(it); // Recursively parse expression
                case VAR -> cog.V(token.text.substring(VAR_PREFIX.length()));
                case SYMBOL -> parseSymbolOrIs(token.text); // Handle True/False/Nil or create Sym
                case NUMBER -> cog.IS(Double.parseDouble(token.text));
                case STRING -> cog.IS(unescapeString(token.text.substring(1, token.text.length() - 1)));
                case RPAREN -> throw new MettaParseException("Unexpected ')' at line " + token.line);
                case COMMENT -> { if (!it.hasNext()) throw new MettaParseException("Input ends with comment"); yield parseAtomFromTokens(it); } // Skip comment
                case EOF -> throw new MettaParseException("Unexpected EOF token type"); // Should not happen if hasNext() check works
            };
        }
        private Expr parseExprFromTokens(PeekableIterator<Token> it) {
            List<Atom> children = new ArrayList<>();
            while (true) {
                if (!it.hasNext()) throw new MettaParseException("Unterminated expression"); var next = it.peek();
                if (next.type == TokenType.RPAREN) { it.next(); return cog.E(children); } // End of expression
                if (next.type == TokenType.COMMENT) { it.next(); continue; } // Skip comments within expression
                children.add(parseAtomFromTokens(it)); // Parse child atom
            }
        }
        public Atom parse(String text) {
            var tokens = tokenize(text); if (tokens.isEmpty()) throw new MettaParseException("Empty input"); var it = new PeekableIterator<>(tokens.iterator()); var result = parseAtomFromTokens(it);
            while (it.hasNext()) { if (it.peek().type != TokenType.COMMENT) throw new MettaParseException("Extra token: " + it.next()); it.next(); } return result; // Ensure no trailing tokens except comments
        }
        public List<Atom> parseAll(String text) {
            var tokens = tokenize(text); List<Atom> results = new ArrayList<>(); var it = new PeekableIterator<>(tokens.iterator());
            while (it.hasNext()) { while (it.hasNext() && it.peek().type == TokenType.COMMENT) it.next(); if (it.hasNext()) results.add(parseAtomFromTokens(it)); } return results; // Parse multiple top-level atoms
        }
        private enum TokenType {LPAREN, RPAREN, SYMBOL, VAR, NUMBER, STRING, COMMENT, EOF}
        private record Token(TokenType type, String text, int line, int col) {}
        private static class PeekableIterator<T> implements Iterator<T> {
            private final Iterator<T> iterator; private @Nullable T nextElement;
            public PeekableIterator(Iterator<T> iterator) { this.iterator = iterator; advance(); }
            @Override public boolean hasNext() { return nextElement != null; }
            @Override public T next() { if (!hasNext()) throw new NoSuchElementException(); var current = nextElement; advance(); return current; }
            public T peek() { if (!hasNext()) throw new NoSuchElementException(); return nextElement; }
            private void advance() { nextElement = iterator.hasNext() ? iterator.next() : null; }
        }
    }
    public static class MettaParseException extends RuntimeException { public MettaParseException(String message) { super(message); } }
    private record Pair<A, B>(A a, B b) {} // General purpose pair record

    /** Initializes core symbols, Is functions, and loads initial bootstrap MeTTa rules. */
    private static class Core {
        private final Cog cog; Core(Cog cog) { this.cog = cog; }

        /** Initializes core components. */
        void initialize() {
            // Assign canonical symbols (retrieved or created via S)
            Cog.SYMBOL_EQ = cog.S("="); Cog.SYMBOL_COLON = cog.S(":"); Cog.SYMBOL_ARROW = cog.S("->"); Cog.SYMBOL_TYPE = cog.S("Type");
            Cog.SYMBOL_TRUE = cog.S("True"); Cog.SYMBOL_FALSE = cog.S("False"); Cog.SYMBOL_SELF = cog.S("Self"); Cog.SYMBOL_NIL = cog.S("Nil");

            initIsFn(); // Define bridges to Java functionality

            cog.load(CORE_METTA_RULES); // Load core rules from MeTTa string

            // Ensure core symbols/types have high confidence/pri
            Stream.of(SYMBOL_EQ, SYMBOL_COLON, SYMBOL_ARROW, SYMBOL_TYPE, SYMBOL_TRUE, SYMBOL_FALSE, SYMBOL_NIL, SYMBOL_SELF,
                            cog.S("Number"), cog.S("Bool"), cog.S("String"), cog.S("Atom"), cog.S("List")) // Include core types
                    .forEach(sym -> { cog.mem.updateTruth(sym, Truth.TRUE); cog.mem.boost(sym, 1.0); });
        }

        /** Defines the grounded `Is` functions that bridge MeTTa to Java code. */
        private void initIsFn() {
            // --- Core Ops ---
            cog.IS("match", args -> (args.size() != 3) ? empty() : Optional.ofNullable( (args.get(0) instanceof Is<?> g && g.value() instanceof Mem ts) ? ts : cog.mem )
                    .map(space -> cog.IS(space.query(args.get(1)).stream().map(ans -> cog.interp.subst(args.get(2), ans.bind())).toList())) ); // (match space pattern template) -> Is<List<Result>>
            cog.IS("eval", args -> args.isEmpty() ? empty() : cog.evalBest(args.getFirst())); // (eval expr) -> Best result atom
            cog.IS("add-atom", args -> args.isEmpty() ? empty() : Optional.of(cog.add(args.getFirst()))); // (add-atom atom) -> Added atom
            cog.IS("remove-atom", args -> Optional.of( (!args.isEmpty() && cog.mem.removeAtomInternal(args.getFirst())) ? SYMBOL_TRUE : SYMBOL_FALSE) ); // (remove-atom atom) -> True/False
            cog.IS("get-value", args -> args.isEmpty() ? empty() : cog.mem.value(args.getFirst()).map(cog::IS)); // (get-value atom) -> Is<Value>
            cog.IS("&self", args -> Optional.of(cog.IS(cog.mem))); // (&self) -> Is<Mem> of current space

            // --- Arithmetic / Comparison (Implemented via Java helpers) ---
            cog.IS("_+", args -> applyNumOp(args, cog, Double::sum)); // Internal primitive for '+'
            cog.IS("_-", args -> applyNumOp(args, cog, (a, b) -> a - b)); // Internal primitive for '-'
            cog.IS("_*", args -> applyNumOp(args, cog, (a, b) -> a * b)); // Internal primitive for '*'
            cog.IS("_/", args -> applyNumOp(args, cog, (a, b) -> Math.abs(b) < 1e-12 ? Double.NaN : a / b)); // Internal primitive for '/'
            cog.IS("_==", args -> applyNumFn(args, cog, (a, b) -> Math.abs(a - b) < 1e-9)); // Internal primitive for '=='
            cog.IS("_>", args -> applyNumFn(args, cog, (a, b) -> a > b)); // Internal primitive for '>'
            cog.IS("_<", args -> applyNumFn(args, cog, (a, b) -> a < b)); // Internal primitive for '<'

            // --- String Ops ---
            cog.IS("Concat", args -> Optional.of(cog.IS(args.stream().map(Core::stringValue).flatMap(Optional::stream).collect(Collectors.joining())))); // (Concat s1 s2 ...) -> Is<String>

            // --- Control Flow / Util ---
            // `If` evaluation is often handled more efficiently directly in the interpreter strategy (see Interp comment) or via rules.
            // Providing an Is function is an alternative but less common for core control flow.
            // Example Is implementation (evaluates condition first):
            cog.IS("If", args -> {
                if (args.size() != 3) return empty();
                var y = cog.evalBest(args.get(0)); // Evaluate condition
                // Return 'then' branch if condition evaluates to True, 'else' branch otherwise (including False, Nil, or no result)
                return y.filter(SYMBOL_TRUE::equals).isPresent()
                        ? Optional.of(args.get(1)) // Return unevaluated 'then' branch (caller evaluates if needed)
                        : Optional.of(args.get(2)); // Return unevaluated 'else' branch
            });
            // `Let` for local bindings (example implementation)
            cog.IS("Let", args -> {
                if (args.size() != 2 || !(args.get(0) instanceof Expr bindingExpr && bindingExpr.head() != null && bindingExpr.head().equals(SYMBOL_EQ) && bindingExpr.children().size() == 3)) {
                    // Expects (Let (= $var <valueExpr>) <bodyExpr>)
                    return empty();
                }
                var varToBind = bindingExpr.children().get(1);
                var valueExpr = bindingExpr.children().get(2);
                var bodyExpr = args.get(1);

                if (!(varToBind instanceof Var v)) return empty(); // Must bind a variable

                // Evaluate the value expression
                var valueResultOpt = cog.evalBest(valueExpr);
                if (valueResultOpt.isEmpty()) return empty(); // Value expression failed to evaluate

                // Create a binding and substitute into the body
                var bind = new Bind(Map.of(v, valueResultOpt.get()));
                var boundBody = cog.unify.subst(bodyExpr, bind);

                // Return the substituted body (caller evaluates if needed)
                return Optional.of(boundBody);
            });
            cog.IS("IsEmpty", args -> args.isEmpty() ? Optional.of(SYMBOL_TRUE) : cog.evalBest(args.get(0)).map(res -> (res instanceof Is<?> g && g.value() instanceof List l && l.isEmpty()) || res.equals(SYMBOL_NIL) ? SYMBOL_TRUE : SYMBOL_FALSE)); // Check if list Is or Nil
            cog.IS("IsNil", args -> args.isEmpty()? Optional.of(SYMBOL_TRUE) : cog.evalBest(args.get(0)).map(res -> res.equals(SYMBOL_NIL) ? SYMBOL_TRUE : SYMBOL_FALSE)); // Check if Nil symbol
            cog.IS("RandomFloat", args -> Optional.of(cog.IS(RandomGenerator.getDefault().nextDouble()))); // Generate random double [0,1)

            // --- Agent Support (Bridge to environment and agent state) ---
            cog.IS("GetEnv", args -> cog.env().map(cog::IS)); // (GetEnv) -> Is<Game> (current environment)
            cog.IS("AgentPerceive", args -> args.isEmpty() ? empty() : cog.evalBest(args.get(0)).flatMap(agentId -> // Expects agent ID (e.g., Self)
                    cog.env().map(env -> {
                        var percepts = env.perceive(cog);
                        // Optional: Process percepts here (add to memory, create state atom)
                        // For now, just return the raw percept list
                        return cog.IS(percepts); // Returns Is<List<Atom>> of percepts
                    })));
            cog.IS("AgentAvailableActions", args -> (args.size() != 2) ? empty() : // Expects (AgentAvailableActions $agent $state)
                    cog.env().flatMap(env ->
                            cog.evalBest(args.get(1)).map(stateAtom -> // Evaluate state argument if needed
                                    cog.IS(env.actions(cog, stateAtom))) // Returns Is<List<Atom>> of actions
                    ));
            cog.IS("AgentExecute", args -> (args.size() != 2) ? empty() : // Expects (AgentExecute $agent $action)
                    cog.env().flatMap(env -> {
                        // Evaluate action argument if needed? Assume action is symbol for now.
                        var actionAtom = args.get(1);
                        var actResult = env.exe(cog, actionAtom);
                        // Return richer result including state change info if needed by learning rules
                        // Simplified: (Act <percepts> <reward>) - requires Act symbol definition
                        return Optional.of(cog.E(cog.S("Act"), cog.IS(actResult.newPercepts()), cog.IS(actResult.reward())));
                    }));
            // AgentLearn, AgentSelectAction, CheckGoal etc. are primarily defined via MeTTa rules using these primitives.

            // --- JVM Integration Placeholders (Requires Java Implementation) ---
            // # MeTTa Integration Pathway: JVM Interaction
            // These functions are critical for enabling MeTTa scripts to interact with the underlying JVM.
            // Implementation requires careful use of Java Reflection, MethodHandles, and potentially Proxies.
            // Security (e.g., class/method allow-listing configured via MeTTa) is paramount.

            // (JavaNew <class_name_string> <arg1> <arg2> ...) -> Is<Object> (new instance)
            cog.IS("JavaNew", args -> { /* TODO: Implement using Reflection Class.forName(<className>).getConstructor(...).newInstance(<args>...) Convert MeTTa args (Is<T>) to Java types. Handle exceptions. Security check className. */ return empty(); });

            // (JavaCall <instance_is> <method_name_string> <arg1> <arg2> ...) -> Is<Result> or Nil
            cog.IS("JavaCall", args -> { /* TODO: Implement using MethodHandles or Reflection. Get instance from Is<Object>. Find method by name/args. Convert args. Invoke. Wrap result in Is<T>. Handle void return (Nil?). Security check method. */ return empty(); });

            // (JavaStaticCall <class_name_string> <method_name_string> <arg1> <arg2> ...) -> Is<Result> or Nil
            cog.IS("JavaStaticCall", args -> { /* TODO: Implement using MethodHandles or Reflection. Find static method. Convert args. Invoke. Wrap result. Handle void. Security check class/method. */ return empty(); });

            // (JavaField <instance_is> <field_name_string>) -> Is<Value> (Get field)
            // (JavaField <instance_is> <field_name_string> <value_to_set>) -> True/False (Set field)
            cog.IS("JavaField", args -> { /* TODO: Implement using MethodHandles or Reflection. Get/Set field value. Convert value. Security check field access. */ return empty(); });

            // (JavaProxy <interface_name_string> <handler_metta_expr>) -> Is<Proxy>
            // Creates a Java Proxy implementing the interface, forwarding calls to evaluate the handler expression.
            cog.IS("JavaProxy", args -> { /* TODO: Implement using java.lang.reflect.Proxy.newProxyInstance. Create InvocationHandler that evaluates <handler_metta_expr> passing method info as args. Security check interface. */ return empty(); });
        }

        // --- Helpers for Is Functions (Kept in Java for performance) ---
        private static Optional<Atom> applyNumOp(List<Atom> args, Cog cog, BinaryOperator<Double> op) {
            if (args.size() != 2) return empty(); var n1 = numValue(args.get(0), cog); var n2 = numValue(args.get(1), cog);
            return n1.isPresent() && n2.isPresent() ? Optional.of(cog.IS(op.apply(n1.get(), n2.get()))) : empty();
        }
        private static Optional<Atom> applyNumFn(List<Atom> args, Cog cog, BiPredicate<Double, Double> op) {
            if (args.size() != 2) return empty(); var n1 = numValue(args.get(0), cog); var n2 = numValue(args.get(1), cog);
            return n1.isPresent() && n2.isPresent() ? Optional.of(op.test(n1.get(), n2.get()) ? SYMBOL_TRUE : SYMBOL_FALSE) : empty();
        }
        private static Optional<Double> numValue(@Nullable Atom atom, Cog cog) { // Eval args if needed
            if (atom instanceof Is<?> g && g.value() instanceof Number n) return Optional.of(n.doubleValue());
            return atom == null ? empty() : cog.evalBest(atom).filter(res -> res instanceof Is<?> g && g.value() instanceof Number).map(res -> ((Number) ((Is<?>) res).value()).doubleValue());
        }
        private static Optional<String> stringValue(@Nullable Atom atom) { // Convert common types to string directly
            if (atom == null) return empty();
            return switch (atom) {
                case Is<?> g when g.value() instanceof String s -> Optional.of(s);
                case Sym s -> Optional.of(s.name());
                case Is<?> g when g.value() instanceof Number n -> Optional.of(n.toString());
                case Atom a when a.equals(SYMBOL_TRUE) -> Optional.of("True");
                case Atom a when a.equals(SYMBOL_FALSE) -> Optional.of("False");
                case Atom a when a.equals(SYMBOL_NIL) -> Optional.of("Nil");
                // Add evaluation step if other atom types should be evaluated to string?
                default -> Optional.empty(); // Or Optional.of(atom.toString()) as ultimate fallback?
            };
        }

        // Core MeTTa rules loaded at startup. Defines basic types and links operators to Is functions.
        private static final String CORE_METTA_RULES = """
            ; Basic Types Declaration (Self-description)
            (: = Type) (: : Type) (: -> Type) (: Type Type)
            (: True Type) (: False Type) (: Nil Type) (: Number Type) (: String Type)
            (: Bool Type) (: Atom Type) (: List Type)
            (: State Type) (: Action Type) (: Goal Type) (: Utility Type) (: Implies Type) (: Seq Type)
            (: GroundedFn Type) ; Type for Is functions

            ; Type Assertions for constants
            (: True Bool) (: False Bool) (: Nil List)
            ; Type assertions for Is functions could be added here or dynamically via reflection if needed
            ; (: _+ GroundedFn) ... etc ...

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
            ; (= (IsEmpty Nil) True)
            ; (= (IsEmpty (Cons $h $t)) False)

            ; Basic Boolean logic (Example)
            ; (= (And True $x) $x)
            ; (= (And False $x) False)
            ; (= (Not True) False)
            ; (= (Not False) True)
        """;
    }

    /** Agent action result structure. */
    public record Act(List<Atom> newPercepts, double reward) {}

    /** Example Game environment. */
    static class SimpleGame implements Game {
        private final Atom posA, posB, posGoal, moveAtoB, moveBtoGoal, moveOther, statePred, selfSym;
        private final Cog cog; private Atom currentStateSymbol;
        SimpleGame(Cog cog) {
            this.cog = cog; posA = cog.S("Pos_A"); posB = cog.S("Pos_B"); posGoal = cog.S("AtGoal");
            moveAtoB = cog.S("Move_A_B"); moveBtoGoal = cog.S("Move_B_Goal"); moveOther = cog.S("Move_Other");
            statePred = cog.S("State"); selfSym = cog.S("Self"); currentStateSymbol = posA;
        }
        @Override public List<Atom> perceive(Cog cog) { return List.of(cog.E(statePred, selfSym, currentStateSymbol)); }
        @Override public List<Atom> actions(Cog cog, Atom currentStateAtom) {
            // Note: currentStateAtom might be a complex state representation evaluated from percepts.
            // This simple game uses internal symbol `currentStateSymbol`. A real agent
            // would likely use the `currentStateAtom` passed as argument.
            if (currentStateSymbol.equals(posA)) return List.of(moveAtoB, moveOther);
            if (currentStateSymbol.equals(posB)) return List.of(moveBtoGoal, moveOther);
            return List.of(moveOther); // Only 'other' if at goal
        }
        @Override public Act exe(Cog cog, Atom actionSymbol) {
            var reward = -0.1; var stateChanged = false; // Cost per step
            if (currentStateSymbol.equals(posA) && actionSymbol.equals(moveAtoB)) { currentStateSymbol = posB; reward = 0.1; stateChanged = true; System.out.println("Env: Moved A -> B"); }
            else if (currentStateSymbol.equals(posB) && actionSymbol.equals(moveBtoGoal)) { currentStateSymbol = posGoal; reward = 1.0; stateChanged = true; System.out.println("Env: Moved B -> Goal!"); }
            else if (actionSymbol.equals(moveOther)) { reward = -0.2; System.out.println("Env: Executed 'Move_Other'"); }
            else { reward = -0.5; System.out.println("Env: Invalid action: " + actionSymbol + " from " + currentStateSymbol); } // Penalty
            return new Act(perceive(cog), reward); // Return new percepts and reward
        }
        @Override public boolean isRunning() { return !currentStateSymbol.equals(posGoal); } // Stop when goal reached
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
            // Ensure agent control symbols are known
            Stream.of("AgentStep", "AgentPerceive", "AgentSelectAction", "AgentExecute", "AgentLearn", "CheckGoal", "UpdateUtility")
                    .forEach(cog::S);
        }

        /**
         * Runs the agent loop. The loop structure is Java, but each step's logic
         * is determined by evaluating the AGENT_STEP_EXPR in MeTTa.
         *
         * # MeTTa Integration Pathway: Agent Control Flow
         *
         * While the *steps* within the loop (perceive, select, learn...) are now driven by evaluating
         * AGENT_STEP_EXPR via MeTTa rules, the loop *itself* (checking cycles, env state, goal) is Java.
         *
         * This loop could also be defined in MeTTa, making the agent's lifecycle fully self-described.
         * This would involve:
         * - Representing cycle count, env state, etc., as Atoms accessible to MeTTa.
         * - Defining MeTTa rules for the loop condition and recursion, e.g.:
         *   ```metta
         *   (= (AgentRunLoop $agent $env $goal $cyclesLeft)
         *      (If (Or (<= $cyclesLeft 0) (IsGoalMet $agent $goal) (not (IsEnvRunning $env)))
         *          (Log "Agent loop finished.") ; Base case: Stop condition
         *          (Seq (AgentStep $agent) ; Execute one step via existing MeTTa rules
         *               (AgentRunLoop $agent $env $goal (- $cyclesLeft 1))))) ; Recurse
         *   ```
         * - The Java `run` method would then simply trigger `cog.evalBest( (AgentRunLoop Self <envIs> <goalAtom> <maxCyclesIs>) )`.
         *
         * This increases self-integration but might have minor performance implications compared to the Java loop.
         * Per the guidelines, modifying `Agent` is lower priority unless crucial for core operation.
         */
        public void run(Game env, Atom goalPattern, int maxCycles) {
            System.out.println("\n--- Agent Run Start (MeTTa Driven Steps) ---");
            initializeGoal(goalPattern); // Set up goal representation in MeTTa space

            for (var cycle = 0; cycle < maxCycles && env.isRunning(); cycle++) {
                var time = cog.tick();
                System.out.printf("\n--- Agent Cycle %d (Time: %d) ---%n", cycle + 1, time);

                // Evaluate the main agent step expression (defined in MeTTa).
                // This evaluation should trigger the MeTTa rules for perceive, select, execute, learn.
                System.out.println("Agent: Evaluating step: " + AGENT_STEP_EXPR);
                var stepResults = cog.eval(AGENT_STEP_EXPR); // THE CORE OF THE AGENT'S "THINKING"

                // Output the results/effects of the step (optional, depends on what AgentStep returns)
                if (!stepResults.isEmpty() && !stepResults.getFirst().equals(AGENT_STEP_EXPR)) {
                    System.out.println("Agent: Step result -> " + stepResults.stream().map(Atom::toString).collect(Collectors.joining(", ")));
                } else if (stepResults.isEmpty()){
                    System.out.println("Agent: Step evaluation yielded no result."); // May indicate error or blocked state
                } else {
                    System.out.println("Agent: Step evaluation resulted in self (no change/action taken by rules).");
                }

                // Check if goal is achieved by evaluating a goal-checking expression (defined in MeTTa)
                // Example: (CheckGoal Self (State Self AtGoal))
                Atom goalCheckExpr = cog.E(cog.S("CheckGoal"), SELF_SYM, goalPattern);
                var goalAchievedOpt = cog.evalBest(goalCheckExpr);
                if (goalAchievedOpt.filter(SYMBOL_TRUE::equals).isPresent()) {
                    System.out.println("*** Agent: Goal Achieved (according to MeTTa CheckGoal rule)! ***");
                    break; // Exit loop
                }

                // Optional: Add delay or other checks between cycles
            }

            // Final status after loop termination
            if (!env.isRunning() && !(cog.evalBest(cog.E(cog.S("CheckGoal"), SELF_SYM, goalPattern)).filter(SYMBOL_TRUE::equals).isPresent())) {
                System.out.println("--- Agent Run Finished (Environment Stopped, Goal Not Met) ---");
            } else if (!(cog.evalBest(cog.E(cog.S("CheckGoal"), SELF_SYM, goalPattern)).filter(SYMBOL_TRUE::equals).isPresent())) {
                System.out.println("--- Agent Run Finished (Max Cycles Reached, Goal Not Met) ---");
            } else {
                System.out.println("--- Agent Run Finished ---");
            }
        }

        /** Adds the goal representation to memory with high priority. */
        private void initializeGoal(Atom goalPattern) {
            // Represent goal as: (Goal Self <pattern>)
            Atom goalAtom = cog.add(cog.E(cog.S("Goal"), SELF_SYM, goalPattern));
            cog.mem.boost(goalAtom, PRI_BOOST_ON_GOAL_FOCUS); // Boost the goal fact itself
            cog.mem.boost(goalPattern, PRI_BOOST_ON_GOAL_FOCUS * 0.8); // Boost the pattern too
            System.out.println("Agent: Goal initialized in MeTTa space -> " + goalAtom);
        }

    }
}