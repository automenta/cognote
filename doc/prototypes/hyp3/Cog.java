package dumb.hyp3;

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
 * This version focuses on identifying and preparing key code paths, particularly within the Agent,
 * for strategic rewriting into MeTTa script, paving the way for deeper self-integration.
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
 * ## Key Enhancements (Iteration 5 - MeTTa Integration Pathfinding):
 * - **Agent Logic Re-architecting:** The `Agent.run` loop and `Agent.selectAction` are modified to delegate their core logic to MeTTa evaluation (`eval`). This allows the agent's behavior to be defined and modified via MeTTa rules loaded into the `Cog`'s `Mem`.
 * - **New `Is` Functions:** Added foundational `Is` functions to `Core.initIsFn` to support the MeTTa-driven agent logic (e.g., `AgentPerceive`, `AgentExecute`, `AgentSelectAction`, `RandomFloat`, `GetEnv`).
 * - **Configuration via MeTTa:** Constants (like `FORGETTING_THRESHOLD`) can now potentially be defined and accessed via MeTTa atoms, allowing runtime modification (demonstrated conceptually).
 * - **JVM Integration Hints:** Added placeholder `Is` functions (`JavaNew`, `JavaCall`, `JavaStaticCall`, `JavaField`) demonstrating pathways for MeTTa to interact with Java reflection and MethodHandles.
 * - **Refined Forgetting:** Maintenance logic remains Java for performance, but thresholds/parameters could be read from MeTTa atoms.
 * - **Interpreter Strategy:** The `Interp`'s evaluation strategy remains Java for now, but is identified as a future candidate for MeTTa definition.
 *
 * @version 5.0 - Strategic MeTTa Integration Pathways
 */
public final class Cog {

    // --- Configuration Constants (Potential MeTTa Candidates) ---
    // These could be loaded from MeTTa atoms like `(: truth-default-sensitivity 1.0)`
    // Allowing runtime modification and reflection. Access would change from static final
    // to querying the Cog's memory, e.g., cog.getConfigDouble("truth-default-sensitivity", 1.0).
    private static final double TRUTH_DEFAULT_SENSITIVITY = 1.0;
    private static final double TRUTH_MIN_CONFIDENCE_MATCH = 0.01;
    private static final double TRUTH_REVISION_CONFIDENCE_THRESHOLD = 0.1;
    private static final double PRI_INITIAL_STI = 0.2;
    private static final double PRI_INITIAL_LTI_FACTOR = 0.1;
    private static final double PRI_STI_DECAY_RATE = 0.08;
    private static final double PRI_LTI_DECAY_RATE = 0.008;
    private static final double PRI_STI_TO_LTI_RATE = 0.02;
    private static final double PRI_BOOST_ON_ACCESS = 0.08;
    private static final double PRI_BOOST_ON_REVISION_MAX = 0.5;
    private static final double PRI_BOOST_ON_GOAL_FOCUS = 0.95;
    private static final double PRI_BOOST_ON_PERCEPTION = 0.75;
    private static final double PRI_MIN_FORGET_THRESHOLD = 0.015; // Candidate for MeTTa config
    private static final long FORGETTING_CHECK_INTERVAL_MS = 12000;
    private static final int FORGETTING_MAX_MEM_SIZE_TRIGGER = 18000; // Candidate for MeTTa config
    private static final int FORGETTING_TARGET_MEM_SIZE_FACTOR = 80; // Candidate for MeTTa config
    private static final int INTERPRETER_DEFAULT_MAX_DEPTH = 15; // Candidate for MeTTa config
    private static final int INTERPRETER_MAX_RESULTS = 50; // Candidate for MeTTa config
    private static final double AGENT_DEFAULT_PERCEPTION_COUNT = 8.0; // Candidate for MeTTa config
    private static final double AGENT_LEARNED_RULE_COUNT = 1.5; // Candidate for MeTTa config
    private static final double AGENT_UTILITY_THRESHOLD = 0.1; // Candidate for MeTTa config
    private static final double AGENT_RANDOM_ACTION_PROBABILITY = 0.05; // Candidate for MeTTa config

    private static final String VAR_PREFIX = "$";
    private static final Set<String> PROTECTED_SYMBOLS = Set.of(
            "=", ":", "->", "Type", "True", "False", "Nil", "Self", "Goal", "State", "Action", "Utility", "Implies", "Seq", // Agent symbols
            "match", "eval", "add-atom", "remove-atom", "get-value", "If", "+", "-", "*", "/", "==", ">", "<", // Core Is ops
            "Number", "Bool", "String", "&self", // Core types and self ref
            "AgentStep", "AgentPerceive", "AgentSelectAction", "AgentExecute", "AgentLearn", "GetEnv", // Agent Is Ops
            "JavaNew", "JavaCall", "JavaStaticCall", "JavaField" // JVM Integration Is Ops
    );

    // --- Core Symbols ---
    public static Atom SYMBOL_EQ, SYMBOL_COLON, SYMBOL_ARROW, SYMBOL_TYPE, SYMBOL_TRUE, SYMBOL_FALSE, SYMBOL_SELF, SYMBOL_NIL;

    public final Mem mem;
    public final Unify unify;
    public final Agent agent;
    private final Interp interp;
    private final MettaParser parser;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong logicalTime = new AtomicLong(0);
    private @Nullable Game environment; // Hold the current environment for the agent

    public Cog() {
        this.mem = new Mem(this::getLogicalTime);
        this.unify = new Unify(this.mem);
        this.interp = new Interp(this);
        this.agent = new Agent(this); // Agent now primarily uses MeTTa eval
        this.parser = new MettaParser(this);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "Cog-Maintenance");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::performMaintenance, FORGETTING_CHECK_INTERVAL_MS, FORGETTING_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);

        new Core(this).initialize(); // Initializes symbols, Is functions, and core MeTTa rules

        System.out.println("Cog (v5.0 MeTTa Integration Pathfinding) Initialized. Init Size: " + mem.size());
    }

    // --- Main Demonstration ---
    public static void main(String[] args) {
        var cog = new Cog();
        try {
            System.out.println("\n--- Cog Synthesized Test Suite (v5.0) ---");

            // --- [1] Parsing & Atom Basics (Same as before) ---
            printSectionHeader(1, "Parsing & Atom Basics");
            var red = cog.parse("Red");
            cog.add(red);
            cog.mem.updateTruth(red, new Truth(0.9, 5.0));
            System.out.println("Parsed Red: " + red + " | Value: " + cog.mem.value(red));

            // --- [2] Unification (Same as before) ---
            printSectionHeader(2, "Unification");
            var fact1 = cog.parse("(Likes Sam Pizza)");
            var pattern1 = cog.parse("(Likes Sam $w)");
            cog.add(fact1);
            testUnification(cog, "Simple Match", pattern1, fact1);

            // --- [3] MeTTa Evaluation - Core Rules (Loaded in Core) ---
            printSectionHeader(3, "MeTTa Evaluation - Core Rules");
            evaluateAndPrint(cog, "Peano Add 2+1", cog.parse("(add (S (S Z)) (S Z))")); // Expect (S (S (S Z)))
            evaluateAndPrint(cog, "Arithmetic (2+3)*4", cog.parse("(* (+ 2.0 3.0) 4.0)")); // Expect is:Double:20.0
            evaluateAndPrint(cog, "If (5 > 3)", cog.parse("(If (> 5.0 3.0) FiveIsGreater ThreeIsGreater)")); // Expect FiveIsGreater

            // --- [4] Pattern Matching Query (`match`) ---
            printSectionHeader(4, "Pattern Matching Query");
            cog.load("(Likes Sam Pizza)\n(Likes Dean Pizza)");
            evaluateAndPrint(cog, "Match Query", cog.parse("(match &self (Likes $p Pizza) $p)")); // Expect is:List:[Sam, Dean]

            // --- [5] Metaprogramming (Adding rules via `add-atom`) ---
            printSectionHeader(5, "Metaprogramming");
            var ruleToAddAtom = cog.parse("(= (NewPred ConceptX) ResultX)");
            evaluateAndPrint(cog, "Add Rule Meta", cog.E(cog.S("add-atom"), ruleToAddAtom)); // Adds the rule
            evaluateAndPrint(cog, "Test New Rule", cog.parse("(NewPred ConceptX)")); // Expect [ResultX]

            // --- [6] Agent Simulation (Now driven by MeTTa evaluation) ---
            printSectionHeader(6, "Agent Simulation (MeTTa Driven)");
            // Define the agent's behavior via MeTTa rules
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
                       (let $utilPattern (= (Utility $act) $val)
                         (let $currentUtil (match &self $utilPattern $val) ; Get current utility (needs processing if list)
                           (let $newUtil (+ $currentUtil (* 0.2 (- $reward $currentUtil))) ; Simplified update
                             (add-atom (= (Utility $act) $newUtil)))))) ; Add/update utility fact

                    ; Action selection strategy: Prefer utility, fallback to random
                    (= (AgentSelectAction $agent ($state . $actions)) ; Input: (State . Actions) list
                       (Let $bestAction (SelectBestUtility $actions)
                            (If (IsNil $bestAction)
                                (SelectRandom $actions) ; Fallback if no utility found
                                $bestAction)))

                    ; Find action with highest utility (simplified - needs proper max over list)
                    ; (= (SelectBestUtility $actions) ...) ; Needs implementation using query/eval/max

                    ; Random selection (placeholder - requires list processing)
                    ; (= (SelectRandom ($action . $rest)) $action) ; Simplistic: just pick first

                    ; Goal Check Rule (Example)
                    (= (CheckGoal $agent $goalPattern)
                       (If (IsEmpty (match &self $goalPattern $goalPattern)) ; Query if goal pattern exists
                           False
                           True))
                    """);

            var env = new SimpleGame(cog); // Uses the Cog instance
            var agentGoal = cog.parse("(State Self AtGoal)"); // Goal defined via MeTTa
            cog.runAgent(env, agentGoal, 10); // Run for a few steps (driven by AgentStep eval)

            System.out.println("\nQuerying learned utilities after MeTTa-driven run:");
            var utilQuery = cog.parse("(Utility $action)");
            var utilResults = cog.query(utilQuery); // Query the utility values directly
            printQueryResults(utilResults);

            // --- [7] JVM Integration (Conceptual Examples) ---
            printSectionHeader(7, "JVM Integration (Conceptual)");
            // Need Java classes accessible for these examples
            // cog.load("(: SomeJavaClass com.example.MyClass)"); // Hypothetical type mapping

            // Evaluate MeTTa expressions intended to call Java
            // evaluateAndPrint(cog, "Java New", cog.parse("(JavaNew com.example.MyClass 10 \"hello\")"));
            // evaluateAndPrint(cog, "Java Call", cog.parse("(JavaCall (JavaNew ...) myMethod 1 2)"));
            // evaluateAndPrint(cog, "Java Static", cog.parse("(JavaStaticCall com.example.MyUtils utilMethod 3)"));
            // evaluateAndPrint(cog, "Java Field Get", cog.parse("(JavaField (JavaNew ...) myField)"));
            // evaluateAndPrint(cog, "Java Field Set", cog.parse("(JavaField (JavaNew ...) myField \"newValue\")"));
            System.out.println("Note: JVM integration examples require corresponding Java classes and enabled Is functions.");


            // --- [8] Forgetting & Maintenance (Same mechanism, params potentially MeTTa) ---
            printSectionHeader(8, "Forgetting & Maintenance");
            System.out.println("Adding temporary atoms...");
            for (int i = 0; i < 50; i++) cog.add(cog.S("Temp_" + UUID.randomUUID().toString().substring(0, 6)));
            var sizeBefore = cog.mem.size();
            System.out.println("Memory size before maintenance: " + sizeBefore);
            System.out.println("Waiting for maintenance cycle...");
            Thread.sleep(FORGETTING_CHECK_INTERVAL_MS + 2000);
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

    // --- Helper Methods ---
    private static void printSectionHeader(int sectionNum, String title) { System.out.printf("\n--- [%d] %s ---\n", sectionNum, title); }
    private static void testUnification(Cog cog, String name, Atom p, Atom i) { System.out.printf("Unify (%s): %s with %s -> %s%n", name, p, i, cog.unify.unify(p, i, Bind.EMPTY).map(Bind::toString).orElse("Failure")); }
    private static void evaluateAndPrint(Cog cog, String name, Atom expr) {
        System.out.println("eval \"" + name + "\" \t " + expr);
        var results = cog.eval(expr);
        System.out.printf(" -> [%s]%n", results.stream().map(Atom::toString).collect(Collectors.joining(", ")));
        // results.forEach(r -> System.out.println("      Value: " + cog.mem.valueOrDefault(r)));
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
        this.environment = env; // Make environment accessible to Is functions
        agent.run(env, goal, maxCycles); // Delegate to the agent instance
        this.environment = null; // Clear environment reference after run
    }

    public void shutdown() {
        scheduler.shutdown();
        try { if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) scheduler.shutdownNow(); }
        catch (InterruptedException e) { scheduler.shutdownNow(); Thread.currentThread().interrupt(); }
        System.out.println("Cog scheduler shut down.");
    }

    private void performMaintenance() { mem.decayAndForget(); }

    /** Retrieve the current game environment (used by Is functions). */
    public Optional<Game> getEnvironment() { return Optional.ofNullable(environment); }

    // --- Core Data Structures ---
    public enum AtomType {SYM, VAR, EXPR, IS}
    public sealed interface Atom { /* ... No changes ... */
        String id(); AtomType type();
        default Sym asSym() { return (Sym) this; } default Var asVar() { return (Var) this; }
        default Expr asExpr() { return (Expr) this; } default Is<?> asIs() { return (Is<?>) this; }
        @Override boolean equals(Object other); @Override int hashCode(); @Override String toString();
    }
    public interface Game { /* ... No changes ... */
        List<Atom> perceive(Cog cog); List<Atom> actions(Cog cog, Atom currentState);
        Act exe(Cog cog, Atom action); boolean isRunning();
    }
    public record Sym(String name) implements Atom { /* ... No changes ... */
        private static final ConcurrentMap<String, Sym> SYMBOL_CACHE = new ConcurrentHashMap<>();
        public static Sym of(String name) { return SYMBOL_CACHE.computeIfAbsent(name, Sym::new); }
        @Override public String id() { return name; } @Override public AtomType type() { return AtomType.SYM; }
        @Override public String toString() { return name; } @Override public int hashCode() { return name.hashCode(); }
        @Override public boolean equals(Object o) { return this == o || (o instanceof Sym(String n1) && name.equals(n1)); }
    }
    public record Var(String name) implements Atom { /* ... No changes ... */
        public Var { if (name.startsWith(VAR_PREFIX)) throw new IllegalArgumentException("Var name excludes prefix"); }
        @Override public String id() { return VAR_PREFIX + name; } @Override public AtomType type() { return AtomType.VAR; }
        @Override public String toString() { return id(); } @Override public int hashCode() { return id().hashCode(); }
        @Override public boolean equals(Object o) { return this == o || (o instanceof Var v && id().equals(v.id())); }
    }
    public record Expr(String id, List<Atom> children) implements Atom { /* ... No changes ... */
        private static final ConcurrentMap<List<Atom>, String> idCache = new ConcurrentHashMap<>();
        public Expr(List<Atom> inputChildren) { this(idCache.computeIfAbsent(List.copyOf(inputChildren), Expr::computeIdInternal), List.copyOf(inputChildren)); }
        private static String computeIdInternal(List<Atom> childList) { return "(" + childList.stream().map(Atom::id).collect(Collectors.joining(" ")) + ")"; }
        @Override public String id() { return id; } @Override public AtomType type() { return AtomType.EXPR; }
        public @Nullable Atom head() { return children.isEmpty() ? null : children.getFirst(); }
        public List<Atom> tail() { return children.isEmpty() ? emptyList() : children.subList(1, children.size()); }
        @Override public String toString() { return id(); } @Override public int hashCode() { return id.hashCode(); }
        @Override public boolean equals(Object o) { return this == o || (o instanceof Expr ea && id.equals(ea.id)); }
    }
    public record Is<T>(String id, @Nullable T value, @Nullable Function<List<Atom>, Optional<Atom>> fn) implements Atom { /* ... Minor change in toString ... */
        public Is(T value) { this(deriveId(value), value, null); }
        public Is(String id, T value) { this(id, value, null); }
        public Is(String name, Function<List<Atom>, Optional<Atom>> fn) { this(name, null, fn); }
        private static <T> String deriveId(T value) { /* ... No change ... */
            if (value == null) return "is:null:null";
            var valStr = value.toString(); var typeName = value.getClass().getSimpleName();
            var valuePart = (value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Atom || valStr.length() < 30)
                    ? valStr : String.valueOf(valStr.hashCode());
            // Handle potential nested structures better (e.g., List<Atom>)
             if (value instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Atom) {
                 valuePart = "[" + list.stream().map(Object::toString).limit(3).collect(Collectors.joining(",")) + (list.size() > 3 ? ",..." : "") + "]";
                 typeName = "List<Atom>";
             }
            return "is:" + typeName + ":" + valuePart;
        }
        @Override public AtomType type() { return AtomType.IS; }
        public boolean isFn() { return fn != null; }
        public Optional<Atom> apply(List<Atom> args) { return isFn() ? fn.apply(args) : empty(); }
        @Override public String toString() { /* ... Refined for clarity ... */
            if (isFn()) return "IsFn<" + id + ">";
            if (value instanceof String s) return "\"" + s + "\"";
            if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
            if (value instanceof Atom a) return a.toString(); // If Is wraps an Atom, show the Atom
            if (value instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Atom) { // Special display for List<Atom>
                return "Is<List:" + list.stream().map(Object::toString).limit(5).collect(Collectors.joining(",", "[", list.size() > 5 ? ",...]" : "]")) + ">";
            }
            var valStr = String.valueOf(value);
            return "Is<" + (value != null ? value.getClass().getSimpleName() : "null") + ":"
                    + (valStr.length() > 20 ? valStr.substring(0, 17) + "..." : valStr) + ">";
        }
        @Override public int hashCode() { return id.hashCode(); }
        @Override public boolean equals(Object o) { return this == o || (o instanceof Is<?> ga && id.equals(ga.id)); }
    }

    // --- Metadata Records ---
    public record Value(Truth truth, Pri pri, Time access) { /* ... No changes ... */
        public static final Value DEFAULT = new Value(Truth.UNKNOWN, Pri.DEFAULT, Time.DEFAULT);
        public Value withTruth(Truth nt) { return new Value(nt, pri, access); }
        public Value withPri(Pri np) { return new Value(truth, np, access); }
        public Value withTime(Time na) { return new Value(truth, pri, na); }
        public Value updateTime(long now) { return new Value(truth, pri, new Time(now)); }
        public Value boost(double amount, long now) { return withPri(pri.boost(amount)).updateTime(now); }
        public Value decay(long now) { return withPri(pri.decay()); }
        public double getCurrentPri(long now) {
            double timeFactor = Math.exp(-Math.max(0, now - access.time()) / (FORGETTING_CHECK_INTERVAL_MS * 3.0));
            return pri.getCurrent(timeFactor) * truth.confidence();
        }
        public double getWeightedTruth() { return truth.confidence() * truth.strength; }
        @Override public String toString() { return truth + " " + pri + " " + access; }
    }
    public record Truth(double strength, double count) { /* ... No changes ... */
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
    public record Pri(double sti, double lti) { /* ... No changes ... */
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
    public record Time(long time) { /* ... No changes ... */
        public static final Time DEFAULT = new Time(0L); @Override public String toString() { return "@" + time; }
    }

    // --- Core Engine Components ---
    public static class Mem { /* ... Minor changes for forgetting config ... */
        private final ConcurrentMap<String, Atom> atomsById = new ConcurrentHashMap<>(1024);
        private final ConcurrentMap<Atom, AtomicReference<Value>> storage = new ConcurrentHashMap<>(1024);
        private final ConcurrentMap<Atom, ConcurrentSkipListSet<String>> indexByHead = new ConcurrentHashMap<>();
        private final Supplier<Long> timeSource;
        public Mem(Supplier<Long> timeSource) { this.timeSource = timeSource; }
        @SuppressWarnings("unchecked") public <A extends Atom> A add(A atom) { /* ... No changes ... */
            var canonicalAtom = (A) atomsById.computeIfAbsent(atom.id(), id -> atom);
            long now = timeSource.get();
            var valueRef = storage.computeIfAbsent(canonicalAtom, k -> {
                var initVal = Value.DEFAULT.withPri(new Pri(PRI_INITIAL_STI, PRI_INITIAL_STI * PRI_INITIAL_LTI_FACTOR)).updateTime(now);
                updateIndices(k); return new AtomicReference<>(initVal);
            });
            valueRef.updateAndGet(v -> v.boost(PRI_BOOST_ON_ACCESS * 0.1, now));
            checkMemoryAndTriggerForgetting();
            return canonicalAtom;
        }
        public Optional<Atom> getAtom(String id) { return Optional.ofNullable(atomsById.get(id)).map(this::boostAndGet); }
        public Optional<Atom> getAtom(Atom atom) { return Optional.ofNullable(atomsById.get(atom.id())).map(this::boostAndGet); }
        private Atom boostAndGet(Atom atom) { updateValue(atom, v -> v.boost(PRI_BOOST_ON_ACCESS, timeSource.get())); return atom; }
        public Optional<Value> value(Atom atom) { return Optional.ofNullable(atomsById.get(atom.id())).flatMap(key -> Optional.ofNullable(storage.get(key))).map(AtomicReference::get); }
        public Value valueOrDefault(Atom atom) { return value(atom).orElse(Value.DEFAULT); }
        public void updateValue(Atom atom, Function<Value, Value> updater) { /* ... No changes ... */
            var canonicalAtom = atomsById.get(atom.id()); if (canonicalAtom == null) return;
            var valueRef = storage.get(canonicalAtom);
            if (valueRef != null) {
                long now = timeSource.get();
                valueRef.updateAndGet(current -> {
                    var updated = updater.apply(current).updateTime(now);
                    var confDiff = updated.truth.confidence() - current.truth.confidence();
                    var boost = (confDiff > TRUTH_REVISION_CONFIDENCE_THRESHOLD) ? PRI_BOOST_ON_REVISION_MAX * updated.truth.confidence() : 0.0;
                    return boost > 0 ? updated.boost(boost, now) : updated;
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
            Is<Function<List<Atom>, Optional<Atom>>> is = new Is<>(name, fn);
            add(is);
            return is;
        }

        public List<Answer> query(Atom pattern) { /* ... No changes ... */
            var queryPattern = add(pattern); boost(queryPattern, PRI_BOOST_ON_ACCESS * 0.2);
            Stream<Atom> candidateStream;
            if (queryPattern instanceof Expr pExpr && pExpr.head() != null) {
                candidateStream = indexByHead.getOrDefault(pExpr.head(), new ConcurrentSkipListSet<>()).stream().map(this::getAtom).flatMap(Optional::stream);
                candidateStream = Stream.concat(candidateStream, Stream.of(queryPattern).filter(storage::containsKey));
            } else if (queryPattern instanceof Var) { candidateStream = storage.keySet().stream();
            } else { candidateStream = Stream.of(queryPattern).filter(storage::containsKey); }
            List<Answer> results = new ArrayList<>(); var unification = new Unify(this);
            var checkCount = 0; var maxChecks = Math.min(5000 + size() / 10, 15000);
            var it = candidateStream.iterator();
            while (it.hasNext() && results.size() < INTERPRETER_MAX_RESULTS && checkCount < maxChecks) {
                var candidate = it.next(); checkCount++;
                if (valueOrDefault(candidate).truth.confidence() < TRUTH_MIN_CONFIDENCE_MATCH) continue;
                unification.unify(queryPattern, candidate, Bind.EMPTY).ifPresent(bind -> {
                    boost(candidate, PRI_BOOST_ON_ACCESS); results.add(new Answer(candidate, bind));
                });
            }
            results.sort(Comparator.comparingDouble((Answer ans) -> valueOrDefault(ans.resultAtom()).getWeightedTruth()).reversed());
            return results.stream().limit(INTERPRETER_MAX_RESULTS).toList();
        }
        synchronized void decayAndForget() { /* ... Use configured threshold/target ... */
            final long now = timeSource.get(); var initialSize = storage.size(); if (initialSize == 0) return;
            List<Atom> candidatesForRemoval = new ArrayList<>(); var decayCount = 0;
            for (var entry : storage.entrySet()) {
                var atom = entry.getKey(); var valueRef = entry.getValue();
                var isProtected = (atom instanceof Sym s && PROTECTED_SYMBOLS.contains(s.name())) || atom instanceof Var;
                var decayedValue = valueRef.updateAndGet(v -> v.decay(now)); decayCount++;
                // Use PRI_MIN_FORGET_THRESHOLD (could be fetched from MeTTa config)
                if (!isProtected && decayedValue.getCurrentPri(now) < PRI_MIN_FORGET_THRESHOLD) {
                    candidatesForRemoval.add(atom);
                }
            }
            var removedCount = 0;
            // Use FORGETTING constants (could be fetched from MeTTa config)
            var targetSize = FORGETTING_MAX_MEM_SIZE_TRIGGER * FORGETTING_TARGET_MEM_SIZE_FACTOR / 100;
            var memoryPressure = initialSize > FORGETTING_MAX_MEM_SIZE_TRIGGER;
            var significantLowPri = candidatesForRemoval.size() > initialSize * 0.05;
            if (!candidatesForRemoval.isEmpty() && (memoryPressure || significantLowPri || initialSize > targetSize)) {
                candidatesForRemoval.sort(Comparator.comparingDouble(atom -> valueOrDefault(atom).getCurrentPri(now)));
                var removalTargetCount = memoryPressure ? Math.max(0, initialSize - targetSize) : candidatesForRemoval.size();
                var actuallyRemoved = 0;
                for (var atomToRemove : candidatesForRemoval) {
                    if (actuallyRemoved >= removalTargetCount) break;
                    if (valueOrDefault(atomToRemove).getCurrentPri(now) < PRI_MIN_FORGET_THRESHOLD) {
                        if (removeAtomInternal(atomToRemove)) actuallyRemoved++;
                    }
                } removedCount = actuallyRemoved;
            }
            if (removedCount > 0 || decayCount > 0 && initialSize > 10) {
                 System.out.printf("Memory Maintenance: Decayed %d atoms. Removed %d low-pri atoms (Threshold=%.3f). Size %d -> %d.%n",
                       decayCount, removedCount, PRI_MIN_FORGET_THRESHOLD, initialSize, storage.size());
            }
        }
        boolean removeAtomInternal(Atom atom) { /* ... No changes ... */
             if (storage.remove(atom) != null) { atomsById.remove(atom.id()); removeIndices(atom); return true; } return false;
        }
        private void checkMemoryAndTriggerForgetting() { if (storage.size() > FORGETTING_MAX_MEM_SIZE_TRIGGER) CompletableFuture.runAsync(this::decayAndForget); }
        private void updateIndices(Atom atom) { if (atom instanceof Expr e && e.head() != null) indexByHead.computeIfAbsent(e.head(), h -> new ConcurrentSkipListSet<>()).add(atom.id()); }
        private void removeIndices(Atom atom) { if (atom instanceof Expr e && e.head() != null) indexByHead.computeIfPresent(e.head(), (k, v) -> { v.remove(atom.id()); return v.isEmpty() ? null : v; }); }
        public int size() { return storage.size(); }
    }
    public record Bind(Map<Var, Atom> map) { /* ... No changes ... */
        public static final Bind EMPTY = new Bind(emptyMap()); public Bind { map = Map.copyOf(map); }
        public boolean isEmpty() { return map.isEmpty(); } public Optional<Atom> get(Var var) { return Optional.ofNullable(map.get(var)); }
        public Optional<Atom> getRecursive(Var var) {
            var current = map.get(var); if (current == null) return empty(); Set<Var> visited = new HashSet<>(); visited.add(var);
            while (current instanceof Var v) { if (!visited.add(v)) return empty(); var next = map.get(v); if (next == null) return Optional.of(v); current = next; }
            return Optional.of(current);
        }
        @Override public String toString() { return map.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", ", "{", "}")); }
    }
    public record Answer(Atom resultAtom, Bind bind) {}

    public static class Unify { /* ... No changes ... */
        private final Mem space; public Unify(Mem space) { this.space = space; }
        public Optional<Bind> unify(Atom pattern, Atom instance, Bind initialBind) {
            Deque<Pair<Atom, Atom>> stack = new ArrayDeque<>(); stack.push(new Pair<>(pattern, instance)); var currentBinds = initialBind;
            while (!stack.isEmpty()) {
                var task = stack.pop(); var p = subst(task.a, currentBinds); var i = subst(task.b, currentBinds);
                if (p.equals(i)) continue;
                if (p instanceof Var pVar) { if (containsVar(i, pVar)) return empty(); currentBinds = mergeBind(currentBinds, pVar, i); if (currentBinds == null) return empty(); continue; }
                if (i instanceof Var iVar) { if (containsVar(p, iVar)) return empty(); currentBinds = mergeBind(currentBinds, iVar, p); if (currentBinds == null) return empty(); continue; }
                if (p instanceof Expr pExpr && i instanceof Expr iExpr) {
                    var pChildren = pExpr.children(); var iChildren = iExpr.children(); var pn = pChildren.size(); if (pn != iChildren.size()) return empty();
                    for (var j = pn - 1; j >= 0; j--) stack.push(new Pair<>(pChildren.get(j), iChildren.get(j))); continue;
                }
                return empty();
            } return Optional.of(currentBinds);
        }
        public Atom subst(Atom atom, Bind bind) {
            if (bind.isEmpty() || !(atom instanceof Var || atom instanceof Expr)) return atom;
            return switch (atom) {
                case Var var -> bind.getRecursive(var).map(val -> subst(val, bind)).orElse(var);
                case Expr expr -> {
                    var changed = false; List<Atom> newChildren = new ArrayList<>(expr.children().size());
                    for (var child : expr.children()) { var substChild = subst(child, bind); if (child != substChild) changed = true; newChildren.add(substChild); }
                    yield changed ? new Expr(newChildren) : expr;
                } default -> atom;
            };
        }
        private boolean containsVar(Atom expr, Var var) {
            return switch (expr) { case Var v -> v.equals(var); case Expr e -> e.children().stream().anyMatch(c -> containsVar(c, var)); default -> false; };
        }
        private @Nullable Bind mergeBind(Bind current, Var var, Atom value) {
            var existingBindOpt = current.getRecursive(var);
            if (existingBindOpt.isPresent()) return unify(existingBindOpt.get(), value, current).orElse(null);
            else { var m = new HashMap<>(current.map()); m.put(var, value); return new Bind(m); }
        }
    }

    public class Interp { /* ... No changes (but identified as future candidate) ... */
        // NOTE: The evaluation strategy logic within evalRecursive (order of rule matching, Is execution, child eval)
        // is a potential future candidate for being defined via MeTTa rules itself, enabling meta-interpretation.
        private final Cog cog; public Interp(Cog cog) { this.cog = cog; }
        public Atom subst(Atom atom, Bind bind) { return cog.unify.subst(atom, bind); }
        public List<Atom> eval(Atom atom, int maxDepth) {
            var results = evalRecursive(atom, maxDepth, new HashSet<>());
            return results.isEmpty() ? List.of(atom) : results.stream().distinct().limit(INTERPRETER_MAX_RESULTS).toList();
        }
        private List<Atom> evalRecursive(Atom atom, int depth, Set<String> visitedInPath) {
            if (depth <= 0 || !visitedInPath.add(atom.id())) return List.of(atom);
            List<Atom> combinedResults = new ArrayList<>();
            try {
                switch (atom) {
                    case Sym s -> combinedResults.add(s); case Var v -> combinedResults.add(v);
                    case Is<?> ga when !ga.isFn() -> combinedResults.add(ga);
                    case Expr expr -> {
                        // Strategy 1: Specific equality rule `(= <expr> $result)`
                        var resultVar = V("evalRes" + depth); Atom specificQuery = E(SYMBOL_EQ, expr, resultVar);
                        var specificMatches = cog.mem.query(specificQuery);
                        if (!specificMatches.isEmpty()) {
                            for (var match : specificMatches) {
                                match.bind.get(resultVar).ifPresent(target ->
                                    combinedResults.addAll(evalRecursive(target, depth - 1, new HashSet<>(visitedInPath))));
                                if (combinedResults.size() >= INTERPRETER_MAX_RESULTS) break;
                            }
                        }
                        // Strategy 2: General equality rule `(= <pattern> <template>)`
                        if (combinedResults.size() < INTERPRETER_MAX_RESULTS) {
                            var pVar = V("p" + depth); var tVar = V("t" + depth); Atom generalQuery = E(SYMBOL_EQ, pVar, tVar);
                            var ruleMatches = cog.mem.query(generalQuery);
                            for (var ruleMatch : ruleMatches) {
                                var pattern = ruleMatch.bind.get(pVar).orElse(null); var template = ruleMatch.bind.get(tVar).orElse(null);
                                if (pattern == null || template == null || pattern.equals(expr)) continue;
                                var exprBindOpt = cog.unify.unify(pattern, expr, Bind.EMPTY);
                                if (exprBindOpt.isPresent()) {
                                    var result = subst(template, exprBindOpt.get());
                                    combinedResults.addAll(evalRecursive(result, depth - 1, new HashSet<>(visitedInPath)));
                                    if (combinedResults.size() >= INTERPRETER_MAX_RESULTS) break;
                                }
                            }
                        }
                        // Strategy 3: Is Function execution (Applicative Order)
                        if (combinedResults.size() < INTERPRETER_MAX_RESULTS && expr.head() instanceof Is<?> ga && ga.isFn()) {
                            List<Atom> evaluatedArgs = new ArrayList<>(); var argEvalOk = true;
                            for (var arg : expr.tail()) {
                                var argResults = evalRecursive(arg, depth - 1, new HashSet<>(visitedInPath));
                                if (argResults.size() != 1) { argEvalOk = false; break; } // Require single result for args
                                evaluatedArgs.add(argResults.getFirst());
                            }
                            if (argEvalOk) {
                                ga.apply(evaluatedArgs).ifPresent(execResult ->
                                    combinedResults.addAll(evalRecursive(execResult, depth - 1, new HashSet<>(visitedInPath))));
                            }
                        }
                        // Strategy 4: Evaluate children and reconstruct (if no rules/exec applied)
                        if (combinedResults.isEmpty()) {
                            var childrenChanged = false; List<Atom> evaluatedChildren = new ArrayList<>();
                            if (expr.head() != null) {
                                var headResults = evalRecursive(expr.head(), depth - 1, new HashSet<>(visitedInPath));
                                var newHead = (headResults.size() == 1) ? headResults.getFirst() : expr.head();
                                evaluatedChildren.add(newHead); if (!newHead.equals(expr.head())) childrenChanged = true;
                            }
                            for (var child : expr.tail()) {
                                var childResults = evalRecursive(child, depth - 1, new HashSet<>(visitedInPath));
                                var newChild = (childResults.size() == 1) ? childResults.getFirst() : child;
                                evaluatedChildren.add(newChild); if (!newChild.equals(child)) childrenChanged = true;
                            } combinedResults.add(childrenChanged ? cog.E(evaluatedChildren) : expr);
                        }
                        if (combinedResults.isEmpty()) combinedResults.add(expr); // Fallback: Expr evaluates to itself
                    }
                    case Is<?> ga -> combinedResults.add(ga); // Fn Is evaluates to self if evaluated directly
                }
            } finally { visitedInPath.remove(atom.id()); }
            return combinedResults;
        }
    }

    private static class MettaParser { /* ... No changes ... */
        private final Cog cog; MettaParser(Cog cog) { this.cog = cog; }
        private Atom parseSymbolOrIs(String text) {
             return switch (text) { case "True" -> SYMBOL_TRUE; case "False" -> SYMBOL_FALSE; case "Nil" -> SYMBOL_NIL; default -> cog.S(text); };
        }
        private String unescapeString(String s) { return s.replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\"); }
        private List<Token> tokenize(String text) { /* ... Logic unchanged ... */
             List<Token> tokens = new ArrayList<>(); var line = 1; var col = 1; var i = 0;
             while (i < text.length()) {
                 var c = text.charAt(i); var startCol = col;
                 if (Character.isWhitespace(c)) { if (c == '\n') { line++; col = 1; } else col++; i++; continue; }
                 if (c == '(') { tokens.add(new Token(TokenType.LPAREN, "(", line, startCol)); i++; col++; continue; }
                 if (c == ')') { tokens.add(new Token(TokenType.RPAREN, ")", line, startCol)); i++; col++; continue; }
                 if (c == ';') { while (i < text.length() && text.charAt(i) != '\n') i++; if (i < text.length() && text.charAt(i) == '\n') { line++; col = 1; i++; } else col = 1; continue; }
                 if (c == '"') { var start = i; i++; col++; var sb = new StringBuilder(); var escaped = false;
                     while (i < text.length()) { var nc = text.charAt(i); if (nc == '"' && !escaped) { i++; col++; tokens.add(new Token(TokenType.STRING, text.substring(start, i), line, startCol)); break; }
                         if (nc == '\n') throw new MettaParseException("Unterminated string at line " + line); sb.append(nc); escaped = (nc == '\\' && !escaped); i++; col++; }
                     if (i == text.length() && (tokens.isEmpty() || tokens.getLast().type != TokenType.STRING)) throw new MettaParseException("Unterminated string at EOF"); continue;
                 }
                 if (c == VAR_PREFIX.charAt(0)) { var start = i; i++; col++; while (i < text.length() && !Character.isWhitespace(text.charAt(i)) && text.charAt(i) != '(' && text.charAt(i) != ')') { i++; col++; }
                     var varName = text.substring(start, i); if (varName.length() == 1) throw new MettaParseException("Invalid var name '$' at line " + line); tokens.add(new Token(TokenType.VAR, varName, line, startCol)); continue;
                 }
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
                 catch (NumberFormatException e) { tokens.add(new Token(TokenType.SYMBOL, value, line, startCol)); }
             } return tokens;
        }
        private Atom parseAtomFromTokens(PeekableIterator<Token> it) { /* ... Logic unchanged ... */
             if (!it.hasNext()) throw new MettaParseException("Unexpected EOF"); var token = it.next();
             return switch (token.type) {
                 case LPAREN -> parseExprFromTokens(it);
                 case VAR -> cog.V(token.text.substring(VAR_PREFIX.length()));
                 case SYMBOL -> parseSymbolOrIs(token.text);
                 case NUMBER -> cog.IS(Double.parseDouble(token.text));
                 case STRING -> cog.IS(unescapeString(token.text.substring(1, token.text.length() - 1)));
                 case RPAREN -> throw new MettaParseException("Unexpected ')' at line " + token.line);
                 case COMMENT -> { if (!it.hasNext()) throw new MettaParseException("Input ends with comment"); yield parseAtomFromTokens(it); }
                 case EOF -> throw new MettaParseException("Unexpected EOF");
             };
        }
        private Expr parseExprFromTokens(PeekableIterator<Token> it) { /* ... Logic unchanged ... */
             List<Atom> children = new ArrayList<>();
             while (true) {
                 if (!it.hasNext()) throw new MettaParseException("Unterminated expression"); var next = it.peek();
                 if (next.type == TokenType.RPAREN) { it.next(); return cog.E(children); }
                 if (next.type == TokenType.COMMENT) { it.next(); continue; }
                 children.add(parseAtomFromTokens(it));
             }
        }
        public Atom parse(String text) { /* ... Logic unchanged ... */
             var tokens = tokenize(text); if (tokens.isEmpty()) throw new MettaParseException("Empty input"); var it = new PeekableIterator<>(tokens.iterator()); var result = parseAtomFromTokens(it);
             while (it.hasNext()) { if (it.peek().type != TokenType.COMMENT) throw new MettaParseException("Extra token: " + it.next()); it.next(); } return result;
        }
        public List<Atom> parseAll(String text) { /* ... Logic unchanged ... */
             var tokens = tokenize(text); List<Atom> results = new ArrayList<>(); var it = new PeekableIterator<>(tokens.iterator());
             while (it.hasNext()) { while (it.hasNext() && it.peek().type == TokenType.COMMENT) it.next(); if (it.hasNext()) results.add(parseAtomFromTokens(it)); } return results;
        }
        private enum TokenType {LPAREN, RPAREN, SYMBOL, VAR, NUMBER, STRING, COMMENT, EOF}
        private record Token(TokenType type, String text, int line, int col) {}
        private static class PeekableIterator<T> implements Iterator<T> { /* ... Logic unchanged ... */
             private final Iterator<T> iterator; private T nextElement;
             public PeekableIterator(Iterator<T> iterator) { this.iterator = iterator; advance(); }
             @Override public boolean hasNext() { return nextElement != null; }
             @Override public T next() { if (!hasNext()) throw new NoSuchElementException(); var current = nextElement; advance(); return current; }
             public T peek() { if (!hasNext()) throw new NoSuchElementException(); return nextElement; }
             private void advance() { nextElement = iterator.hasNext() ? iterator.next() : null; }
        }
    }
    public static class MettaParseException extends RuntimeException { public MettaParseException(String message) { super(message); } }
    private record Pair<A, B>(A a, B b) {}

    private static class Core { /* ... Added Agent and JVM Is functions ... */
        private final Cog cog; Core(Cog cog) { this.cog = cog; }
        void initialize() {
            Cog.SYMBOL_EQ = cog.S("="); Cog.SYMBOL_COLON = cog.S(":"); Cog.SYMBOL_ARROW = cog.S("->"); Cog.SYMBOL_TYPE = cog.S("Type");
            Cog.SYMBOL_TRUE = cog.S("True"); Cog.SYMBOL_FALSE = cog.S("False"); Cog.SYMBOL_SELF = cog.S("Self"); Cog.SYMBOL_NIL = cog.S("Nil");
            initIsFn(); // Define grounded functions
            cog.load(CORE_METTA_RULES); // Load core rules from MeTTa string
            // Ensure core symbols have high confidence/pri
            Stream.of(SYMBOL_EQ, SYMBOL_COLON, SYMBOL_ARROW, SYMBOL_TYPE, SYMBOL_TRUE, SYMBOL_FALSE, SYMBOL_NIL, SYMBOL_SELF)
                  .forEach(sym -> { cog.mem.updateTruth(sym, Truth.TRUE); cog.mem.boost(sym, 1.0); });
        }

        private void initIsFn() {
            // --- Core Ops ---
            cog.IS("match", args -> (args.size() != 3) ? empty() : Optional.ofNullable( (args.get(0) instanceof Is<?> g && g.value() instanceof Mem ts) ? ts : cog.mem )
                    .map(space -> cog.IS(space.query(args.get(1)).stream().map(ans -> cog.interp.subst(args.get(2), ans.bind())).toList())) );
            cog.IS("eval", args -> args.isEmpty() ? empty() : cog.evalBest(args.getFirst()));
            cog.IS("add-atom", args -> args.isEmpty() ? empty() : Optional.of(cog.add(args.getFirst())));
            cog.IS("remove-atom", args -> Optional.of( (!args.isEmpty() && cog.mem.removeAtomInternal(args.getFirst())) ? SYMBOL_TRUE : SYMBOL_FALSE) );
            cog.IS("get-value", args -> args.isEmpty() ? empty() : cog.mem.value(args.getFirst()).map(cog::IS)); // Wrap Value in Is
            cog.IS("&self", args -> Optional.of(cog.IS(cog.mem))); // Reference to own space

            // --- Arithmetic / Comparison ---
            cog.IS("_+", args -> applyNumOp(args, cog, Double::sum));
            cog.IS("_-", args -> applyNumOp(args, cog, (a, b) -> a - b));
            cog.IS("_*", args -> applyNumOp(args, cog, (a, b) -> a * b));
            cog.IS("_/", args -> applyNumOp(args, cog, (a, b) -> Math.abs(b) < 1e-12 ? Double.NaN : a / b));
            cog.IS("_==", args -> applyNumFn(args, cog, (a, b) -> Math.abs(a - b) < 1e-9));
            cog.IS("_>", args -> applyNumFn(args, cog, (a, b) -> a > b));
            cog.IS("_<", args -> applyNumFn(args, cog, (a, b) -> a < b));

            // --- String Ops ---
            cog.IS("Concat", args -> Optional.of(cog.IS(args.stream().map(Core::stringValue).flatMap(Optional::stream).collect(Collectors.joining()))));

            // --- Control Flow / Util ---
            cog.IS("If", args -> (args.size() != 3) ? empty() : cog.evalBest(args.get(0)).flatMap(cond -> Optional.of(cond.equals(SYMBOL_TRUE) ? args.get(1) : args.get(2)))); // Simplified If evaluation
            cog.IS("Let", args -> (args.size() != 2 || !(args.get(0) instanceof Expr e && e.children().size() == 2)) ? empty() : // Expect (Let (= $var <val_expr>) <body_expr>)
                     cog.unify.unify(e.children().get(0), e.children().get(1), Bind.EMPTY).flatMap(bind -> cog.evalBest(cog.unify.subst(args.get(1), bind))) );
            cog.IS("IsEmpty", args -> args.isEmpty() ? Optional.of(SYMBOL_TRUE) : cog.evalBest(args.get(0)).map(res -> (res instanceof Is<?> g && g.value() instanceof List l && l.isEmpty()) || res.equals(SYMBOL_NIL) ? SYMBOL_TRUE : SYMBOL_FALSE));
            cog.IS("IsNil", args -> args.isEmpty()? Optional.of(SYMBOL_TRUE) : cog.evalBest(args.get(0)).map(res -> res.equals(SYMBOL_NIL) ? SYMBOL_TRUE : SYMBOL_FALSE));
            cog.IS("RandomFloat", args -> Optional.of(cog.IS(RandomGenerator.getDefault().nextDouble())));

            // --- Agent Support ---
            cog.IS("GetEnv", args -> cog.getEnvironment().map(cog::IS)); // Get current environment
            cog.IS("AgentPerceive", args -> args.isEmpty() ? empty() : cog.evalBest(args.get(0)).flatMap(agentId -> // Expects agent ID (e.g., Self)
                     cog.getEnvironment().map(env -> cog.IS(env.perceive(cog))))); // Returns Is<List<Atom>> of percepts
            cog.IS("AgentAvailableActions", args -> (args.size() != 2) ? empty() : // Expects (AgentAvailableActions $agent $state)
                     cog.getEnvironment().map(env -> cog.IS(env.actions(cog, args.get(1))))); // Returns Is<List<Atom>> of actions
            cog.IS("AgentExecute", args -> (args.size() != 2) ? empty() : // Expects (AgentExecute $agent $action)
                     cog.getEnvironment().flatMap(env -> {
                         var actionAtom = args.get(1);
                         var actResult = env.exe(cog, actionAtom);
                         // Return richer result: (Act <percepts> <reward> <prevState> <action> <nextState>) - needs state tracking
                         // Simplified return for now: (Act <percepts> <reward>)
                         return Optional.of(cog.E(cog.S("Act"), cog.IS(actResult.newPercepts()), cog.IS(actResult.reward())));
                     }));
            // AgentLearn, AgentSelectAction, CheckGoal etc. are typically defined via MeTTa rules calling these primitives

            // --- JVM Integration Placeholders ---
            // These require careful implementation with security considerations (e.g., class allowlisting)
            cog.IS("JavaNew", args -> { /* TODO: Impl using Reflection Class.forName/getConstructor/newInstance */ return empty(); });
            cog.IS("JavaCall", args -> { /* TODO: Impl using Reflection/MethodHandles invoke */ return empty(); });
            cog.IS("JavaStaticCall", args -> { /* TODO: Impl using Reflection/MethodHandles invokeStatic */ return empty(); });
            cog.IS("JavaField", args -> { /* TODO: Impl using Reflection/MethodHandles get/set field */ return empty(); });
            cog.IS("JavaProxy", args -> { /* TODO: Impl Proxy.newProxyInstance with MeTTa handler */ return empty(); });
        }
        // Helper: Apply binary double operation
        private static Optional<Atom> applyNumOp(List<Atom> args, Cog cog, BinaryOperator<Double> op) {
            if (args.size() != 2) return empty();
            var n1 = numValue(args.get(0), cog); var n2 = numValue(args.get(1), cog);
            return n1.isPresent() && n2.isPresent() ? Optional.of(cog.IS(op.apply(n1.get(), n2.get()))) : empty();
        }
        // Helper: Apply binary double comparison
        private static Optional<Atom> applyNumFn(List<Atom> args, Cog cog, BiPredicate<Double, Double> op) {
            if (args.size() != 2) return empty();
            var n1 = numValue(args.get(0), cog); var n2 = numValue(args.get(1), cog);
            return n1.isPresent() && n2.isPresent() ? Optional.of(op.test(n1.get(), n2.get()) ? SYMBOL_TRUE : SYMBOL_FALSE) : empty();
        }
        // Helper: Get Double value, evaluating if necessary
        private static Optional<Double> numValue(@Nullable Atom atom, Cog cog) {
             if (atom instanceof Is<?> g && g.value() instanceof Number n) return Optional.of(n.doubleValue());
             return atom == null ? empty() : cog.evalBest(atom).filter(res -> res instanceof Is<?> g && g.value() instanceof Number).map(res -> ((Number) ((Is<?>) res).value()).doubleValue());
        }
        // Helper: Get String value, evaluating if necessary
        private static Optional<String> stringValue(@Nullable Atom atom) {
            if (atom == null) return empty();
            if (atom instanceof Is<?> g && g.value() instanceof String s) return Optional.of(s);
            if (atom instanceof Sym s) return Optional.of(s.name());
            if (atom instanceof Is<?> g && g.value() instanceof Number n) return Optional.of(n.toString());
            if (SYMBOL_TRUE.equals(atom)) return Optional.of("True");
            if (SYMBOL_FALSE.equals(atom)) return Optional.of("False");
            // Add evaluation step if needed, similar to numValue
            return empty();
        }

        // Core MeTTa rules loaded at startup
        private static final String CORE_METTA_RULES = """
            ; Basic Types
            (: = Type) (: : Type) (: -> Type) (: Type Type) (: True Type) (: False Type)
            (: Nil Type) (: Number Type) (: String Type) (: Bool Type) (: Atom Type) (: List Type)
            (: State Type) (: Action Type) (: Goal Type) (: Utility Type) (: Implies Type) (: Seq Type)

            ; Type Instances
            (: True Bool) (: False Bool) (: Self Atom) (: Nil List)

            ; Peano Arithmetic Example Rules
            (= (add Z $n) $n)
            (= (add (S $m) $n) (S (add $m $n)))

            ; Basic Arithmetic/Comparison rules linking symbols to Is functions
            (= (+ $a $b) (_+ $a $b))
            (= (- $a $b) (_- $a $b))
            (= (* $a $b) (_* $a $b))
            (= (/ $a $b) (_/ $a $b))
            (= (== $a $b) (_== $a $b))
            (= (> $a $b) (_> $a $b))
            (= (< $a $b) (_< $a $b))
            ; (= (<= $a $b) (Not (> $a $b))) ; Example derived rule
            ; (= (>= $a $b) (Not (< $a $b))) ; Example derived rule

            ; If control structure (basic rule - execution handled by Is Fn for efficiency)
            ; (= (If True $then $else) $then)
            ; (= (If False $then $else) $else)

            ; Basic List processing (conceptual - requires list Is functions)
            ; (= (head (Cons $h $t)) $h)
            ; (= (tail (Cons $h $t)) $t)
            ; (= (IsEmpty Nil) True)
            ; (= (IsEmpty (Cons $h $t)) False)
        """;
    }

    public record Act(List<Atom> newPercepts, double reward) {} // Agent action result

    static class SimpleGame implements Game { /* ... No changes ... */
         private final Atom posA, posB, posGoal, moveAtoB, moveBtoGoal, moveOther, statePred, selfSym;
         private final Cog cog; private Atom currentStateSymbol;
         SimpleGame(Cog cog) {
             this.cog = cog; posA = cog.S("Pos_A"); posB = cog.S("Pos_B"); posGoal = cog.S("AtGoal");
             moveAtoB = cog.S("Move_A_B"); moveBtoGoal = cog.S("Move_B_Goal"); moveOther = cog.S("Move_Other");
             statePred = cog.S("State"); selfSym = cog.S("Self"); currentStateSymbol = posA;
         }
         @Override public List<Atom> perceive(Cog cog) { return List.of(cog.E(statePred, selfSym, currentStateSymbol)); }
         @Override public List<Atom> actions(Cog cog, Atom currentStateAtom) {
             // Simplistic: base actions on internal symbol, not the passed stateAtom
             if (currentStateSymbol.equals(posA)) return List.of(moveAtoB, moveOther);
             if (currentStateSymbol.equals(posB)) return List.of(moveBtoGoal, moveOther);
             return List.of(moveOther);
         }
         @Override public Act exe(Cog cog, Atom actionSymbol) {
             var reward = -0.1; var stateChanged = false;
             if (currentStateSymbol.equals(posA) && actionSymbol.equals(moveAtoB)) { currentStateSymbol = posB; reward = 0.1; stateChanged = true; System.out.println("Env: Moved A -> B"); }
             else if (currentStateSymbol.equals(posB) && actionSymbol.equals(moveBtoGoal)) { currentStateSymbol = posGoal; reward = 1.0; stateChanged = true; System.out.println("Env: Moved B -> Goal!"); }
             else if (actionSymbol.equals(moveOther)) { reward = -0.2; System.out.println("Env: Executed 'Move_Other'"); }
             else { reward = -0.5; System.out.println("Env: Invalid action: " + actionSymbol + " from " + currentStateSymbol); }
             return new Act(perceive(cog), reward);
         }
         @Override public boolean isRunning() { return !currentStateSymbol.equals(posGoal); }
    }

    /**
     * Agent whose core behavior (perceive-select-execute-learn cycle) is driven by evaluating MeTTa expressions.
     */
    public class Agent {
        private final Cog cog;
        private final Atom AGENT_STEP_EXPR; // Expression to evaluate for each agent step, e.g., (AgentStep Self)
        private final Atom SELF_SYM;

        public Agent(Cog cog) {
            this.cog = cog;
            this.SELF_SYM = cog.S("Self"); // Agent identifier
            this.AGENT_STEP_EXPR = cog.E(cog.S("AgentStep"), SELF_SYM);
            // Ensure agent symbols are added if not already protected/core
            Stream.of("State", "Goal", "Action", "Utility", "Implies", "Seq", // Core concepts
                      "AgentStep", "AgentPerceive", "AgentSelectAction", "AgentExecute", "AgentLearn") // Control flow
                  .forEach(cog::S);
        }

        /**
         * Runs the agent loop. The loop itself is simple Java, but each step's logic
         * is determined by evaluating the AGENT_STEP_EXPR in MeTTa.
         */
        public void run(Game env, Atom goalPattern, int maxCycles) {
            System.out.println("\n--- Agent Run Start (MeTTa Driven) ---");
            initializeGoal(goalPattern); // Set up goal in memory

            // The agent's state representation is now managed within MeTTa space,
            // potentially through facts like (CurrentState Self <state_atom>)

            for (var cycle = 0; cycle < maxCycles && env.isRunning(); cycle++) {
                var time = cog.tick();
                System.out.printf("\n--- Agent Cycle %d (Time: %d) ---%n", cycle + 1, time);

                // Evaluate the main agent step expression.
                // This evaluation should trigger MeTTa rules for perceive, select, execute, learn.
                System.out.println("Agent: Evaluating step: " + AGENT_STEP_EXPR);
                var stepResults = cog.eval(AGENT_STEP_EXPR); // Evaluate the agent's "program"

                // Output the results/effects of the step (if any informative result is returned)
                if (!stepResults.isEmpty() && !stepResults.getFirst().equals(AGENT_STEP_EXPR)) {
                     System.out.println("Agent: Step result -> " + stepResults.stream().map(Atom::toString).collect(Collectors.joining(", ")));
                } else if (stepResults.isEmpty()){
                     System.out.println("Agent: Step evaluation yielded no result.");
                } else {
                     System.out.println("Agent: Step evaluation resulted in self (no change/action).");
                }

                // Check if goal is achieved by evaluating a goal-checking expression
                Atom goalCheckExpr = cog.E(cog.S("CheckGoal"), SELF_SYM, goalPattern);
                var goalAchievedOpt = cog.evalBest(goalCheckExpr);
                if (goalAchievedOpt.isPresent() && goalAchievedOpt.get().equals(SYMBOL_TRUE)) {
                    System.out.println("*** Agent: Goal Achieved (according to MeTTa check)! ***");
                    break;
                }
            }

            // Final status message
            if (!env.isRunning() && !(cog.evalBest(cog.E(cog.S("CheckGoal"), SELF_SYM, goalPattern)).filter(SYMBOL_TRUE::equals).isPresent())) {
                System.out.println("--- Agent Run Finished (Environment Stopped, Goal Not Met) ---");
            } else if (!(cog.evalBest(cog.E(cog.S("CheckGoal"), SELF_SYM, goalPattern)).filter(SYMBOL_TRUE::equals).isPresent())) {
                System.out.println("--- Agent Run Finished (Max Cycles Reached, Goal Not Met) ---");
            } else {
                System.out.println("--- Agent Run Finished ---");
            }
        }

        /** Adds the goal representation to memory. */
        private void initializeGoal(Atom goalPattern) {
            Atom goalAtom = cog.add(cog.E(cog.S("Goal"), SELF_SYM, goalPattern)); // e.g., (Goal Self (State Self AtGoal))
            cog.mem.boost(goalAtom, PRI_BOOST_ON_GOAL_FOCUS);
            cog.mem.boost(goalPattern, PRI_BOOST_ON_GOAL_FOCUS * 0.8);
            System.out.println("Agent: Goal initialized -> " + goalAtom);
            // Add agent state representation if needed, e.g. (CurrentState Self InitialState)
        }

        // NOTE: The following methods are largely replaced by MeTTa rules and Is functions.
        // They are kept here for conceptual reference or potential fallback mechanisms.

        /**
         * [Replaced by MeTTa evaluation of AgentPerceive]
         * Processes percepts, adds them to the space, and returns a representative state Atom.
         */
        private Atom processPerception_JavaImpl(List<Atom> percepts) {
             if (percepts.isEmpty()) return cog.S("State_Unknown"); // Handle empty perception
             List<String> perceptIDs = Collections.synchronizedList(new ArrayList<>());
             percepts.forEach(p -> {
                 var factAtom = cog.add(p);
                 cog.mem.updateTruth(factAtom, new Truth(1.0, AGENT_DEFAULT_PERCEPTION_COUNT));
                 cog.mem.boost(factAtom, PRI_BOOST_ON_PERCEPTION);
                 perceptIDs.add(factAtom.id());
             });
             Collections.sort(perceptIDs);
             var stateHash = Integer.toHexString(String.join("|", perceptIDs).hashCode());
             var stateAtom = cog.S("State_" + stateHash);
             cog.add(stateAtom); cog.mem.updateTruth(stateAtom, Truth.TRUE); cog.mem.boost(stateAtom, PRI_BOOST_ON_PERCEPTION);
             // Optionally assert (CurrentState Self State_XYZ) here
             return stateAtom;
        }

        /**
         * [Replaced by MeTTa evaluation of AgentSelectAction]
         * Selects an action based on learned utility or explores randomly.
         */
        private Optional<Atom> selectAction_JavaImpl(List<Atom> availableActions, Atom currentStateAtom) {
             if (availableActions.isEmpty()) return empty();
             Map<Atom, Double> utilities = new ConcurrentHashMap<>();
             var valVar = cog.V("val");
             availableActions.parallelStream().forEach(action -> {
                 var actionAtom = cog.add(action);
                 Atom utilQuery = cog.E(SYMBOL_EQ, cog.E(cog.S("Utility"), actionAtom), valVar); // (= (Utility <action>) $val)
                 var utility = cog.mem.query(utilQuery).stream()
                         .map(answer -> answer.bind().get(valVar)).flatMap(Optional::stream)
                         .filter(atom -> atom instanceof Is<?> g && g.value() instanceof Number)
                         .mapToDouble(atom -> ((Number) ((Is<?>) atom).value()).doubleValue())
                         .max().orElse(0.0);
                 if (utility > AGENT_UTILITY_THRESHOLD) utilities.put(actionAtom, utility);
             });
             var bestAction = utilities.entrySet().stream().max(Map.Entry.comparingByValue());
             if (bestAction.isPresent()) {
                 System.out.printf("Agent(JavaImpl): Selecting by utility: %s (Util: %.3f)%n", bestAction.get().getKey(), bestAction.get().getValue());
                 return Optional.of(bestAction.get().getKey());
             }
             if (RandomGenerator.getDefault().nextDouble() < AGENT_RANDOM_ACTION_PROBABILITY || utilities.isEmpty()) {
                 System.out.println("Agent(JavaImpl): Selecting random action.");
                 return Optional.of(cog.add(availableActions.get(RandomGenerator.getDefault().nextInt(availableActions.size()))));
             }
             // Fallback: least bad known action
             return utilities.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey);
        }

        /**
         * [Replaced by MeTTa evaluation triggered by AgentLearn]
         * Learns state transition rules and updates action utility based on experience.
         */
        private void learn_JavaImpl(Atom prevState, Atom action, Atom nextState, double reward) {
            if (prevState == null || action == null || nextState == null) return;
            // 1. Transition Rule: (Implies (Seq <prevState> <action>) <nextState>)
            Atom sequence = cog.add(cog.E(cog.S("Seq"), prevState, action));
            Atom implication = cog.add(cog.E(cog.S("Implies"), sequence, nextState));
            cog.mem.updateTruth(implication, new Truth(1.0, AGENT_LEARNED_RULE_COUNT));
            cog.mem.boost(implication, PRI_BOOST_ON_ACCESS * 1.2);

            // 2. Utility Rule: (= (Utility <action>) <value>) - Simplified TD Update
            var valVar = cog.V("val");
            Atom utilityPattern = cog.E(cog.S("Utility"), action);
            Atom utilityRulePattern = cog.E(SYMBOL_EQ, utilityPattern, valVar);
            double currentUtility = cog.mem.query(utilityRulePattern).stream()
                .map(answer -> answer.bind().get(valVar)).flatMap(Optional::stream)
                .filter(atom -> atom instanceof Is<?> g && g.value() instanceof Number)
                .mapToDouble(atom -> ((Number) ((Is<?>) atom).value()).doubleValue())
                .findFirst().orElse(0.0);
            var learningRate = 0.2;
            var newUtility = currentUtility + learningRate * (reward - currentUtility);
            Atom newUtilityValueAtom = cog.IS(newUtility);
            Atom newUtilityRule = cog.EQ(utilityPattern, newUtilityValueAtom); // Adds/updates the rule
            cog.mem.updateTruth(newUtilityRule, Truth.TRUE);
            cog.mem.boost(newUtilityRule, PRI_BOOST_ON_ACCESS * 1.5);
            System.out.printf("  Learn(JavaImpl): Action %s Utility %.3f -> %.3f (Reward: %.2f)%n", action.id(), currentUtility, newUtility, reward);
        }
    }
}