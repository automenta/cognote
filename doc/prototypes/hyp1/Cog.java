package dumb.hyp1;

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

import static dumb.pln.Cog.unitize;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

/**
 * Cog - Cognitive Logic Engine (Synthesized Hyperon-Inspired Iteration)
 * <p>
 * A unified cognitive architecture implementing a Hyperon/MeTTa-like symbolic system.
 * It integrates pattern matching, rule-based rewriting, probabilistic reasoning,
 * attention mechanisms, and agent interaction within a single, consolidated Java class structure.
 * <p>
 * ## Core Design:
 * - **Atomspace:** Central knowledge store holding immutable `Atom` objects (Symbols, Variables, Expressions, Grounded).
 * - **Unified Representation:** All concepts, relations, rules, procedures, types, states, goals are Atoms.
 * - **MeTTa Execution:** An `Interpreter` evaluates expressions by matching against equality (`=`) rules via `Unification`.
 * - **Homoiconicity:** Code (MeTTa rules/expressions) *is* data (Atoms), enabling reflection and self-modification.
 * - **Grounded Atoms:** Bridge to Java code/data for I/O, math, environment interaction, etc.
 * - **Metadata:** Immutable `Value` records (holding `TruthValue`, `ImportanceValue`, `TimeValue`) associated with Atoms via `AtomicReference` for atomic updates.
 * - **Probabilistic & Importance Driven:** Truth values handle uncertainty; Importance values (STI/LTI) guide attention and forgetting.
 * - **Agent Framework:** Includes a basic agent model demonstrating perception, reasoning (via evaluation), action, and learning (by adding rules).
 * - **Modern Java:** Leverages Records, Sealed Interfaces, Streams, Concurrent Collections, AtomicReference for robustness and conciseness.
 * <p>
 * ## Key Design Choices Synthesized:
 * - Atom Hierarchy: Based on E (SymbolAtom, VariableAtom, ExpressionAtom, Is).
 * - Metadata Storage: Based on D (AtomicReference<Value> holding immutable records).
 * - Interpreter: Separate class like E (MeTTaInterpreter) handling evaluation logic, depth, cycles.
 * - Unification: Separate utility class like B/E (Unification) with occurs check and stack-based approach (inspired by A/E).
 * - API Helpers: Static methods like C (S, V, E, G) for convenient Atom creation.
 * - Bootstrapping: Dedicated static Bootstrap class like D.
 *
 * @version 4.0 - Synthesis
 */
public final class Cog {

    // --- Configuration Constants ---
    private static final double TRUTH_DEFAULT_SENSITIVITY = 1.0; // k in confidence = count / (count + k)
    private static final double TRUTH_MIN_CONFIDENCE_MATCH = 0.01; // Min confidence for an atom to be considered in matching/relevance
    private static final double TRUTH_REVISION_CONFIDENCE_THRESHOLD = 0.1; // Min confidence diff for revision boost

    private static final double IMPORTANCE_INITIAL_STI = 0.2;
    private static final double IMPORTANCE_INITIAL_LTI_FACTOR = 0.1;
    private static final double IMPORTANCE_STI_DECAY_RATE = 0.08; // Slightly faster decay
    private static final double IMPORTANCE_LTI_DECAY_RATE = 0.008;
    private static final double IMPORTANCE_STI_TO_LTI_RATE = 0.02;
    private static final double IMPORTANCE_BOOST_ON_ACCESS = 0.08;
    private static final double IMPORTANCE_BOOST_ON_REVISION_MAX = 0.5;
    private static final double IMPORTANCE_BOOST_ON_GOAL_FOCUS = 0.95;
    private static final double IMPORTANCE_BOOST_ON_PERCEPTION = 0.75;
    private static final double IMPORTANCE_MIN_FORGET_THRESHOLD = 0.015; // Combined importance threshold
    private static final long FORGETTING_CHECK_INTERVAL_MS = 12000;
    private static final int FORGETTING_MAX_MEM_SIZE_TRIGGER = 18000;
    private static final int FORGETTING_TARGET_MEM_SIZE_FACTOR = 80; // %

    private static final int INTERPRETER_DEFAULT_MAX_DEPTH = 15; // Max recursive evaluation depth
    private static final int INTERPRETER_MAX_RESULTS = 50; // Limit non-deterministic results per evaluation step

    private static final double AGENT_DEFAULT_PERCEPTION_COUNT = 8.0;
    private static final double AGENT_LEARNED_RULE_COUNT = 1.5;
    private static final double AGENT_UTILITY_THRESHOLD = 0.1;
    private static final double AGENT_RANDOM_ACTION_PROBABILITY = 0.05;

    private static final String VAR_PREFIX = "$"; // MeTTa variable prefix convention
    private static final Set<String> PROTECTED_SYMBOLS = Set.of( // Symbols protected from forgetting
            "=", ":", "->", "Type", "True", "False", "Self", "Goal", "State", "Action",
            "match", "eval", "add-atom", "remove-atom", "get-value", "If", "+", "-", "*", "/", // Core grounded ops
            "Number", "Bool", "&self" // Core types and self ref
    );

    // --- Core Symbols (initialized in Bootstrap) ---
    // Use static import or direct reference where needed. Defined in Bootstrap for clarity.
    public static Sym SYMBOL_EQ;
    public static Sym SYMBOL_COLON;
    public static Sym SYMBOL_ARROW;
    public static Sym SYMBOL_TYPE;
    public static Sym SYMBOL_TRUE;
    public static Sym SYMBOL_FALSE;
    public static Sym SYMBOL_SELF;
    public static Sym SYMBOL_NIL; // Represents empty list/result often

    // --- System Components ---
    public final Memory space;
    public final Unify unify;
    public final Agent agent;
    private final Interp interp;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong logicalTime = new AtomicLong(0);

    public Cog() {
        this.space = new Memory(this::getLogicalTime);
        this.unify = new Unify(this.space); // Pass space if needed for type checks during unification
        this.interp = new Interp(this.space, this.unify);
        this.agent = new Agent(this);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "Cog-Maintenance");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::performMaintenance, FORGETTING_CHECK_INTERVAL_MS, FORGETTING_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);

        new Core(); // Initialize core symbols, types, grounded atoms

        System.out.println("Cog (v4.0 Synthesis) Initialized. Bootstrap Size: " + space.size());
    }

    public static void main(String[] args) {
        var cog = new Cog();
        var space = cog.space;
        try {
            System.out.println("\n--- Cog Synthesized Test Suite ---");

            // --- [1] Atom Basics & Atomspace ---
            printSectionHeader(1, "Atom Basics & Atomspace");
            var red = cog.S("Red");
            var x = cog.V("x");
            var lovesFritz = cog.E(cog.S("Loves"), cog.S("Self"), cog.S("Fritz"));
            var numPi = cog.G(3.14159);
            var selfRef = space.getAtom(cog.S("&self")).get().asGrounded(); // Retrieve bootstrapped &self

            cog.add(red);
            cog.add(x);
            cog.add(lovesFritz);
            cog.add(numPi);

            System.out.println("Added Atoms: " + red + ", " + x + ", " + lovesFritz + ", " + numPi + ", " + selfRef);
            System.out.println("Retrieved Fritz Atom: " + space.getAtom(lovesFritz));
            System.out.println("Retrieved Pi Atom by ID: " + space.getAtom(numPi.id()));
            System.out.println("Default Value for Red: " + space.value(red));

            space.updateTruth(red, new Truth(0.9, 5.0));
            space.boost(red, 0.5);
            System.out.println("Updated Value for Red: " + space.value(red));

            space.updateTruth(red, new Truth(0.95, 15.0)); // Merge truth
            System.out.println("Merged Value for Red: " + space.value(red));

            // --- [2] Unification ---
            printSectionHeader(2, "Unification");
            var likes = cog.S("Likes");
            var sam = cog.S("Sam");
            var pizza = cog.S("Pizza");
            var p = cog.V("p");
            var w = cog.V("w");
            var fact1 = cog.E(likes, sam, pizza);
            var pattern1 = cog.E(likes, sam, w); // (Likes Sam $w)
            var pattern2 = cog.E(likes, p, pizza); // (Likes $p Pizza)
            var pattern3 = cog.E(likes, p, w); // (Likes $p $w)
            var patternFail = cog.E(cog.S("Hates"), sam, pizza); // (Hates Sam Pizza)

            testUnification(cog, "Simple Match", pattern1, fact1);
            testUnification(cog, "Variable Match 1", pattern2, fact1);
            testUnification(cog, "Variable Match 2", pattern3, fact1);
            testUnification(cog, "Mismatch", patternFail, fact1);
            testUnification(cog, "Occurs Check Fail", x, cog.E(likes, x));

            // --- [3] MeTTa Evaluation - Simple Rules ---
            printSectionHeader(3, "MeTTa Evaluation - Simple Rules");

            // Peano Arithmetic Example
            var z = cog.S("Z"); // Zero
            var s = cog.S("S"); // Successor
            var add = cog.S("add");
            var n = cog.V("n");
            var m = cog.V("m");

            // (= (add Z $n) $n)
            cog.EQ(cog.E(add, z, n), n);

            // (= (add (S $m) $n) (S (add $m $n)))
            cog.EQ(cog.E(add, cog.E(s, m), n), cog.E(s, cog.E(add, m, n)));

            var one = cog.E(s, z);
            var two = cog.E(s, one);
            var exprAdd1 = cog.E(add, one, one); // (add (S Z) (S Z))
            var exprAdd2 = cog.E(add, two, one); // (add (S (S Z)) (S Z))

            evaluateAndPrint(cog, "Peano Add 1+1", exprAdd1); // Expect (S (S Z))
            evaluateAndPrint(cog, "Peano Add 2+1", exprAdd2); // Expect (S (S (S Z)))

            // --- [4] MeTTa Evaluation - Grounded Atoms & Control Flow ---
            printSectionHeader(4, "MeTTa Evaluation - Grounded Atoms & Control Flow");
            // Arithmetic (using bootstrapped '+' etc linked to Grounded*)
            var exprArith1 = cog.E(cog.S("+"), cog.G(5.0), cog.G(3.0));
            evaluateAndPrint(cog, "Arithmetic 5+3", exprArith1); // Expect Grounded<Double:8.0>

            var exprArith2 = cog.E(cog.S("*"), cog.E(cog.S("+"), cog.G(2.0), cog.G(3.0)), cog.G(4.0)); // (* (+ 2 3) 4)
            evaluateAndPrint(cog, "Arithmetic (2+3)*4", exprArith2); // Expect Grounded<Double:20.0>

            // Comparison
            var exprComp = cog.E(cog.S(">"), cog.G(5.0), cog.G(3.0));
            evaluateAndPrint(cog, "Comparison 5 > 3", exprComp); // Expect True

            // If statement
            var exprIfTrue = cog.E(cog.S("If"), SYMBOL_TRUE, cog.S("ResultA"), cog.S("ResultB"));
            evaluateAndPrint(cog, "If True", exprIfTrue); // Expect ResultA

            var exprIfCond = cog.E(cog.S("If"), exprComp, cog.S("FiveIsGreater"), cog.S("ThreeIsGreater"));
            evaluateAndPrint(cog, "If (5 > 3)", exprIfCond); // Expect FiveIsGreater

            // --- [5] Pattern Matching Query (`query`) ---
            printSectionHeader(5, "Pattern Matching Query");
            var dean = cog.S("Dean");
            cog.add(cog.E(likes, sam, pizza)); // Already added
            cog.add(cog.E(likes, dean, pizza));
            cog.add(cog.E(likes, sam, cog.S("Apples")));

            var queryPizza = cog.E(likes, p, pizza); // (Likes $p Pizza)
            System.out.println("Querying: " + queryPizza);
            var pizzaResults = cog.query(queryPizza);
            printQueryResults(pizzaResults); // Expect matches for Sam and Dean

            // --- [6] Truth & Importance Values ---
            printSectionHeader(6, "Truth & Importance Values");
            var flies = cog.S("Flies");
            var penguin = cog.S("Penguin");
            cog.TYPE(penguin, cog.S("Bird"));
            var penguinFlies = cog.add(cog.E(flies, penguin));

            space.updateTruth(penguinFlies, new Truth(0.1, 20.0)); // Penguins likely don't fly
            space.boost(penguinFlies, 0.7);
            System.out.println("Penguin Flies Value: " + space.value(penguinFlies));

            var birdFlies = cog.add(cog.E(flies, cog.S("Bird"))); // Generic bird likely flies
            space.updateTruth(birdFlies, new Truth(0.9, 15.0));
            System.out.println("Bird Flies Value: " + space.value(birdFlies));
            // Merging example not explicit here, but happens internally via updateTruth

            // --- [7] Forgetting ---
            printSectionHeader(7, "Forgetting");
            var protectedAtom = cog.sym("ImportantConcept");
            cog.add(protectedAtom); // Ensure it exists
            space.boost(protectedAtom, 1.0); // Make very important initially
            System.out.println("Protected atom: " + protectedAtom + ", Initial Value: " + space.value(protectedAtom));

            System.out.println("Adding 100 low-importance temporary atoms...");
            for (var i = 0; i < 100; i++) {
                var temp = cog.S("Temp_" + i + "_" + UUID.randomUUID().toString().substring(0, 4)); // Unique name
                cog.add(temp);
                space.updateValue(temp, v -> v.withImportance(new Pri(0.001, 0.0001))); // Very low importance
            }
            System.out.println("Atomspace size before wait: " + space.size());
            System.out.println("Waiting for maintenance cycle (approx " + FORGETTING_CHECK_INTERVAL_MS / 1000 + "s)...");
            Thread.sleep(FORGETTING_CHECK_INTERVAL_MS + 2000); // Wait longer than interval

            var sizeAfter = space.size();
            System.out.println("Atomspace size after wait: " + sizeAfter);
            var protectedCheck = space.getAtom(protectedAtom); // Check if protected atom still exists
            System.out.println("Protected atom '" + protectedAtom.name() + "' still exists: " + protectedCheck.isPresent());
            System.out.println("Protected atom value after wait: " + protectedCheck.flatMap(space::value)); // Importance should have decayed slightly

            // --- [8] Agent Simulation ---
            printSectionHeader(8, "Agent Simulation");
            var env = new SimpleGame(cog);
            // Goal is implicitly reaching AtGoal state via Environment logic
            // Explicit goal representation: (= (State Self) AtGoal)
            var agentGoal = cog.E(cog.S("State"), cog.S("Self"), cog.S("AtGoal"));
            cog.runAgent(env, agentGoal, 10); // Run for a few steps

            // Query learned utilities after agent run
            System.out.println("\nQuerying learned utilities:");
            var utilQuery = cog.E(cog.S("Utility"), cog.V("action"));
            var utilResults = cog.query(utilQuery);
            printQueryResults(utilResults);

            // --- [9] Metaprogramming (Adding rules via MeTTa) ---
            printSectionHeader(9, "Metaprogramming");
            var newPred = cog.S("NewPredicate");
            var conceptA = cog.S("ConceptA");
            var resultValue = cog.S("ResultValue");

            // Define the rule to add: (= (NewPredicate ConceptA) ResultValue)
            var ruleToAdd = cog.E(SYMBOL_EQ, cog.E(newPred, conceptA), resultValue);

            // Use the grounded 'add-atom' function to add the rule via evaluation
            var addRuleExpr = cog.E(cog.S("add-atom"), ruleToAdd);
            System.out.println("Evaluating meta-expression to add rule: " + addRuleExpr);
            evaluateAndPrint(cog, "Add Rule Meta", addRuleExpr); // Should return the added rule Atom

            // Verify the rule works
            var testExpr = cog.E(newPred, conceptA);
            System.out.println("\nEvaluating new predicate after meta-add: " + testExpr);
            evaluateAndPrint(cog, "Test New Rule", testExpr); // Expect [ResultValue]

        } catch (Exception e) {
            System.err.println("\n--- ERROR during demonstration ---");
            e.printStackTrace();
        } finally {
            cog.shutdown();
        }
        System.out.println("\n--- Cog Synthesized Test Suite Finished ---");
    }

    // --- Helper Methods for Testing ---
    private static void printSectionHeader(int sectionNum, String title) {
        System.out.printf("\n--- [%d] %s ---\n", sectionNum, title);
    }

    private static void testUnification(Cog cog, String testName, Atom pattern, Atom instance) {
        System.out.print("Unifying (" + testName + "): " + pattern + " with " + instance + " -> ");
        var result = cog.unify.unify(pattern, instance, Bind.EMPTY);
        System.out.println(result.isPresent() ? "Success: " + result.get() : "Failure");
    }

    private static void evaluateAndPrint(Cog cog, String testName, Atom expression) {
        System.out.println("Evaluating (" + testName + "): " + expression);
        var results = cog.eval(expression); // Use default depth
        System.out.print(" -> Results: [");
        System.out.print(results.stream().map(Atom::toString).collect(Collectors.joining(", ")));
        System.out.println("]");
        // Optionally print truth/importance
        // results.forEach(r -> System.out.println("      Value: " + cog.space.getValue(r)));
    }

    private static void printQueryResults(List<Answer> results) {
        if (results.isEmpty()) {
            System.out.println(" -> No matches found.");
        } else {
            results.forEach(qr -> System.out.println(" -> Match: " + qr.resultAtom() + " | Bindings: " + qr.bind));
        }
    }

    /**
     * resolve symbol
     */
    public Atom S(String name) {
        return space._add(Sym.of(name));
    }

    /**
     * used internally for registering builtins, guarantees return of Sym instance.  interned
     */
    private Sym sym(String name) {
        return space.add(Sym.of(name));
    }

    public Var V(String name) {
        return space.add(new Var(name));
    }

    public Expr E(Atom... children) {
        return space.add(new Expr(List.of(children)));
    }

    public Expr E(List<Atom> children) {
        return space.add(new Expr(children));
    }

    /**
     * Creates and adds a Is wrapping a non-executable value. ID derived from value.
     */
    public <T> Is<T> G(T value) {
        return space.is(value);
    }

    /**
     * Creates and adds a Is wrapping an executable function.
     */
    public Is<Function<List<Atom>, Optional<Atom>>> G(String name, Function<List<Atom>, Optional<Atom>> exec) {
        return space.isExe(name, exec);
    }

    public long getLogicalTime() {
        return logicalTime.get();
    }

    public long tick() {
        return logicalTime.incrementAndGet();
    }

    /**
     * Adds an Atom to the Atomspace, returning the canonical instance.
     */
    public <A extends Atom> A add(A atom) {
        return space.add(atom);
    }

    /**
     * Adds an equality rule `(= premise conclusion)`.
     */
    public Expr EQ(Atom premise, Atom conclusion) {
        return add(E(SYMBOL_EQ, premise, conclusion));
    }

    /**
     * Adds a type assertion `(: instance type)`.
     */
    public Expr TYPE(Atom instance, Atom type) {
        return add(E(SYMBOL_COLON, instance, type));
    }

    /**
     * Evaluates an expression using the MeTTa interpreter.
     */
    public List<Atom> eval(Atom expr) {
        return interp.eval(expr, INTERPRETER_DEFAULT_MAX_DEPTH);
    }

    public List<Atom> eval(Atom expr, int maxDepth) {
        return interp.eval(expr, maxDepth);
    }


    /**
     * Convenience method to get the single 'best' result (highest confidence/strength).
     */
    public Optional<Atom> evalBest(Atom expr) {
        return eval(expr).stream().max(Comparator.comparingDouble(a -> space.valueOrDefault(a).truth.confidence() * space.valueOrDefault(a).truth.strength));
    }

    /**
     * Performs a pattern matching query against the Atomspace.
     */
    public List<Answer> query(Atom pattern) {
        return space.query(pattern);
    }

    /**
     * Runs the agent loop within an environment.
     */
    public void runAgent(Game env, Atom goal, int maxCycles) {
        agent.run(env, goal, maxCycles);
    }

    /**
     * Shuts down the maintenance scheduler.
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
     * Performs periodic maintenance.
     */
    private void performMaintenance() {
        space.decayAndForget();
    }

    public enum AtomType {SYMBOL, VARIABLE, EXPRESSION, GROUNDED}

    /**
     * Base sealed interface for all elements in the Atomspace. Atoms are structurally immutable.
     */
    public sealed interface Atom {
        String id(); // Unique structural identifier

        AtomType type();

        // Convenience casting methods
        default Sym asSymbol() {
            return (Sym) this;
        }

        default Var asVariable() {
            return (Var) this;
        }

        default Expr asExpression() {
            return (Expr) this;
        }

        default Is<?> asGrounded() {
            return (Is<?>) this;
        }

        // Force implementation of equals/hashCode based on structural identity
        @Override
        boolean equals(Object other);

        @Override
        int hashCode();

        @Override
        String toString();
    }

    /**
     * Interface for the environment the agent interacts with.
     */
    public interface Game {

        /**
         * @return 'percept'
         */
        List<Atom> perceive(Cog cog);

        /**
         * @return available actions
         */
        List<Atom> actions(Cog cog, Atom currentState);

        Act exe(Cog cog, Atom action);

        boolean isRunning();
    }

    /**
     * Represents named constants, interned for efficiency.
     */
    public record Sym(String name) implements Atom {

        private static final ConcurrentMap<String, Sym> SYMBOLS = new ConcurrentHashMap<>();

        public static Sym of(String name) {
            return SYMBOLS.computeIfAbsent(name, Sym::new);
        }

        @Override
        public String id() {
            return name;
        }

        @Override
        public AtomType type() {
            return AtomType.SYMBOL;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Represents a variable, distinguished by the '$' prefix in its ID.
     */
    public record Var(String name) implements Atom {
        // Ensure name doesn't contain the prefix internally
        public Var {
            if (name.startsWith(VAR_PREFIX))
                throw new IllegalArgumentException("Variable name should not include prefix '$'");
        }

        @Override
        public String id() {
            return VAR_PREFIX + name;
        }

        @Override
        public AtomType type() {
            return AtomType.VARIABLE;
        }

        @Override
        public String toString() {
            return id();
        }
    }

    /**
     * Represents a composite expression (MeTTa lists/trees).
     */
    public record Expr(String computedId, List<Atom> children) implements Atom {
        // Cache IDs for performance - assumes children are effectively immutable (records/interned symbols)
        private static final ConcurrentMap<List<Atom>, String> idCache = new ConcurrentHashMap<>();

        public Expr(List<Atom> _children) {
            var c = List.copyOf(_children); // Ensure immutable children list
            this(idCache.computeIfAbsent(c, Expr::computeIdInternal), c);
        }

        private static String computeIdInternal(List<Atom> childList) {
            return "(" + childList.stream().map(Atom::id).collect(Collectors.joining(" ")) + ")";
        }

        @Override
        public String id() {
            return computedId;
        }

        @Override
        public AtomType type() {
            return AtomType.EXPRESSION;
        }

        public @Nullable Atom head() {
            return children.isEmpty() ? null : children.getFirst();
        }

        public List<Atom> tail() {
            return children.isEmpty() ? emptyList() : children.subList(1, children.size());
        }

        @Override
        public String toString() {
            return id();
        } // Use ID for concise representation

        // equals/hashCode based on cached ID ensures structural equality check is efficient
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            return o instanceof Expr ea && computedId.equals(ea.computedId);
        }

        @Override
        public int hashCode() {
            return computedId.hashCode();
        }
    }

    /**
     * Wraps external Java values or executable logic.
     */
    public record Is<T>(String id, @Nullable T value,
                        @Nullable Function<List<Atom>, Optional<Atom>> executor) implements Atom {
        // Constructor for simple values - ID generated automatically if not provided
        public Is(T value) {
            this(deriveId(value), value, null);
        }

        public Is(String id, T value) {
            this(id, value, null);
        }

        // Constructor for executable logic - Name becomes the ID
        public Is(String name, Function<List<Atom>, Optional<Atom>> executor) {
            this(name, null, executor);
        }

        private static <T> String deriveId(T value) { // Helper to create a default ID
            var valueStr = String.valueOf(value);
            return "G:" + (value != null ? value.getClass().getSimpleName() : "null") + ":" + valueStr.hashCode(); // Use hash for potentially long values
        }

        @Override
        public AtomType type() {
            return AtomType.GROUNDED;
        }

        public boolean isExecutable() {
            return executor != null;
        }

        public Optional<Atom> execute(List<Atom> args) {
            return isExecutable() ? executor.apply(args) : Optional.empty();
        }

        @Override
        public String toString() {
            if (isExecutable()) return "GndFunc<" + id + ">";
            var valueStr = String.valueOf(value);
            return "Gnd<" + (value != null ? value.getClass().getSimpleName() : "null") + ":" + (valueStr.length() > 30 ? valueStr.substring(0, 27) + "..." : valueStr) + ">";
        }

        // Equals/hashCode based on ID, assuming ID uniquely identifies the grounded concept/value/function
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            return o instanceof Is<?> ga && id.equals(ga.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }

    /**
     * Immutable record holding Truth, Importance, and Time metadata.
     */
    public record Value(Truth truth, Pri importance, Time time) {
        public static final Value DEFAULT = new Value(Truth.UNKNOWN, Pri.DEFAULT, Time.DEFAULT);

        // --- Factory / Update Methods ---
        public Value withTruth(Truth newTruth) {
            return new Value(newTruth, importance, time);
        }

        public Value withImportance(Pri newImportance) {
            return new Value(truth, newImportance, time);
        }

        public Value withTime(Time newTime) {
            return new Value(truth, importance, newTime);
        }

        public Value updateTime(long now) {
            return new Value(truth, importance, new Time(now));
        }

        /**
         * Boosts importance and updates access time. Returns a new Value instance.
         */
        public Value boost(double boostAmount, long now) {
            return this.withImportance(importance.boost(boostAmount)).updateTime(now);
        }

        /**
         * Decays importance. Returns a new Value instance. Does not update access time.
         */
        public Value decay(long now) { // 'now' might be used for more complex decay models later
            return this.withImportance(importance.decay());
        }

        /**
         * Calculates combined current importance modulated by confidence.
         */
        public double getCurrentImportance(long now) {
            double timeSinceAccess = Math.max(0, now - time.time());
            var recencyFactor = Math.exp(-timeSinceAccess / (FORGETTING_CHECK_INTERVAL_MS * 3.0)); // Decay over ~3 cycles
            return importance.getCurrent(recencyFactor) * truth.confidence();
        }

        @Override
        public String toString() {
            return truth + " " + importance + " " + time;
        }
    }

    /**
     * Immutable record for probabilistic truth (Strength, Count/Evidence).
     */
    public record Truth(double strength, double count) {
        public static final Truth TRUE = new Truth(1.0, 10.0);
        public static final Truth FALSE = new Truth(0.0, 10.0);
        public static final Truth UNKNOWN = new Truth(0.5, 0.1);

        public Truth { // Validation in canonical constructor
            strength = Math.max(0.0, Math.min(1.0, strength));
            count = Math.max(0.0, count);
        }

        public double confidence() {
            return count / (count + TRUTH_DEFAULT_SENSITIVITY);
        }

        /**
         * Merges with another TruthValue using Bayesian revision.
         */
        public Truth merge(Truth other) {
            if (other == null || other.count == 0) return this;
            if (this.count == 0) return other;
            var totalCount = this.count + other.count;
            return new Truth((this.strength * this.count + other.strength * other.count) / totalCount, totalCount);
        }

        @Override
        public String toString() {
            return String.format("TV<s:%.3f, c:%.2f>", strength, count);
        }
    }

    /**
     * Immutable record for Short-Term (STI) and Long-Term (LTI) Importance.
     */
    public record Pri(double sti, double lti) {
        public static final Pri DEFAULT = new Pri(IMPORTANCE_INITIAL_STI, IMPORTANCE_INITIAL_STI * IMPORTANCE_INITIAL_LTI_FACTOR);

        public Pri { // Validation
            sti = unitize(sti);
            lti = unitize(lti);
        }

        /**
         * Returns a new ImportanceValue after applying decay.
         */
        public Pri decay() {
            var decayedSti = sti * (1 - IMPORTANCE_STI_DECAY_RATE);
            var decayedLti = lti * (1 - IMPORTANCE_LTI_DECAY_RATE) + decayedSti * IMPORTANCE_STI_TO_LTI_RATE * 0.1; // LTI learns slowly from decayed STI
            return new Pri(decayedSti, decayedLti);
        }

        /**
         * Returns a new ImportanceValue after applying boost.
         */
        public Pri boost(double boostAmount) {
            if (boostAmount <= 0) return this;
            var boostedSti = Math.min(1, sti + boostAmount); // Simple additive boost
            var ltiBoost = boostedSti * IMPORTANCE_STI_TO_LTI_RATE * Math.abs(boostAmount); // Learn more if boost large
            var boostedLti = Math.min(1, lti + ltiBoost);
            return new Pri(boostedSti, boostedLti);
        }

        /**
         * Calculates combined importance, weighted by a recency factor (applied externally).
         */
        public double getCurrent(double recencyFactor) {
            return sti * recencyFactor * 0.6 + lti * 0.4;
        }

        @Override
        public String toString() {
            return String.format("IV<sti:%.3f, lti:%.3f>", sti, lti);
        }
    }

    /**
     * Immutable record representing a discrete time point (logical time).
     */
    public record Time(long time) {
        public static final Time DEFAULT = new Time(0L);

        @Override
        public String toString() {
            return "Time<" + time + ">";
        }
    }


    // === Unification & Bindings ============================================


    /**
     * Central knowledge repository managing Atoms and their metadata (Values).
     */
    public static class Memory {
        private final ConcurrentMap<String, Atom> atomsById = new ConcurrentHashMap<>(1024); // ID -> Atom lookup
        private final ConcurrentMap<Atom, AtomicReference<Value>> storage = new ConcurrentHashMap<>(1024); // Atom -> Value Ref
        // Simple Index: Head Atom -> Set<ExpressionAtom ID>
        private final ConcurrentMap<Atom, ConcurrentSkipListSet<String>> indexByHead = new ConcurrentHashMap<>();
        // TODO: Implement more sophisticated indices (e.g., by type, by contained elements) for performance.

        private final Supplier<Long> timeSource;

        public Memory(Supplier<Long> timeSource) {
            this.timeSource = timeSource;
        }

        /**
         * Adds an atom or retrieves it if it exists, initializing/updating metadata.
         */
        @SuppressWarnings("unchecked") // Cast to A is safe due to computeIfAbsent logic
        public <A extends Atom> A add(A atom) {
            return (A) _add(atom);
        }

        private Atom _add(Atom atom) {
            long now = timeSource.get();
            // Ensure canonical Atom instance is used/stored
            var canonicalAtom = atomsById.computeIfAbsent(atom.id(), id -> atom);

            // Ensure metadata exists and update access time/boost slightly
            var valueRef = storage.computeIfAbsent(canonicalAtom, k -> {
                var initialValue = Value.DEFAULT.withImportance(new Pri(IMPORTANCE_INITIAL_STI, IMPORTANCE_INITIAL_STI * IMPORTANCE_INITIAL_LTI_FACTOR)).updateTime(now);
                updateIndices(k); // Index the new atom
                //System.out.println("atom: " + canonicalAtom + " " + atom.getClass().getSimpleName());
                return new AtomicReference<>(initialValue);
            });
            valueRef.updateAndGet(v -> v.boost(IMPORTANCE_BOOST_ON_ACCESS * 0.1, now));
            checkMemoryAndTriggerForgetting();
            return canonicalAtom;
        }

        /**
         * Retrieves an Atom instance by its unique ID string.
         */
        public Optional<Atom> getAtom(String id) {
            return Optional.ofNullable(atomsById.get(id)).map(this::boostAndGet);
        }

        /**
         * Retrieves an Atom instance if present, boosting importance.
         */
        public Optional<Atom> getAtom(Atom atom) {
            return Optional.ofNullable(storage.get(atom)).map(ref -> boostAndGet(atom));
        }

        private Atom boostAndGet(Atom atom) {
            updateValue(atom, v -> v.boost(IMPORTANCE_BOOST_ON_ACCESS, timeSource.get()));
            return atom;
        }

        /**
         * Retrieves the current Value (Truth, Importance, Time) for an Atom.
         */
        public Optional<Value> value(Atom atom) {
            return Optional.ofNullable(storage.get(atom)).map(AtomicReference::get);
        }

        public Value valueOrDefault(Atom atom) {
            return value(atom).orElse(Value.DEFAULT);
        }

        /**
         * Atomically updates the Value associated with an Atom using an updater function.
         */
        public void updateValue(Atom atom, Function<Value, Value> updater) {
            var valueRef = storage.get(atom); // Get existing ref or null
            if (valueRef != null) { // Only update if atom exists
                long now = timeSource.get();
                valueRef.updateAndGet(current -> {
                    var updated = updater.apply(current).updateTime(now); // Apply update and set time
                    // Calculate revision boost based on truth/confidence change
                    var boost = updated.truth.confidence() - current.truth.confidence() > TRUTH_REVISION_CONFIDENCE_THRESHOLD
                            ? IMPORTANCE_BOOST_ON_REVISION_MAX * updated.truth.confidence() : 0.0;
                    return boost > 0 ? updated.boost(boost, now) : updated; // Apply boost if significant change
                });
            }
        }

        /**
         * Updates the truth value, merging with existing truth.
         */
        public void updateTruth(Atom atom, Truth newTruth) {
            updateValue(atom, v -> v.withTruth(v.truth.merge(newTruth)));
        }

        /**
         * Boosts the importance of an atom.
         */
        public void boost(Atom atom, double amount) {
            updateValue(atom, v -> v.boost(amount, timeSource.get()));
        }

        /**
         * Grounded Atom creation methods that add to this space
         */
        public <T> Is<T> is(T value) {
            return add(new Is<>(value));
        }

        public <T> Is<T> is(String id, T value) {
            return add(new Is<>(id, value));
        }

        public Is<Function<List<Atom>, Optional<Atom>>> isExe(String name, Function<List<Atom>, Optional<Atom>> executor) {
            return add(new Is<>(name, executor));
        }

        /**
         * Symbol creation method that adds to this space
         */
        public Sym sym(String name) {
            return add(Sym.of(name));
        }

        /**
         * Variable creation method that adds to this space
         */
        public Var var(String name) {
            return add(new Var(name));
        }

        /**
         * Expression creation method that adds to this space
         */
        public Expr expr(Atom... children) {
            return expr(List.of(children));
        }

        public Expr expr(List<Atom> children) {
            return add(new Expr(children));
        }


        /**
         * Performs pattern matching using the Unification engine.
         */
        public List<Answer> query(Atom pattern) {
            boost(add(pattern), IMPORTANCE_BOOST_ON_ACCESS * 0.5); // Ensure pattern exists & boost

            // Find candidate atoms using indices (simplified: head index)
            Set<Atom> candidates = ConcurrentHashMap.newKeySet(); // Use Set for uniqueness
            if (pattern instanceof Expr pExpr && pExpr.head() != null) {
                var head = pExpr.head();
                indexByHead.computeIfAbsent(head, h -> new ConcurrentSkipListSet<>())
                        .stream().map(this::getAtom) // Get Atom from ID
                        .flatMap(Optional::stream)
                        .forEach(candidates::add);
                // Also consider direct match for the expression itself
                if (storage.containsKey(pattern)) candidates.add(pattern);
            } else if (pattern instanceof Var) {
                candidates.addAll(storage.keySet()); // Variable matches everything initially
            } else { // Symbol or Grounded Atom pattern
                if (storage.containsKey(pattern)) candidates.add(pattern); // Direct match
                // Could also query indexByChild if needed
            }

            List<Answer> results = new ArrayList<>();
            var unification = new Unify(this);
            var checked = 0;
            var maxChecks = 5000 + candidates.size(); // Limit checks

            for (var candidate : candidates) {
                if (checked++ > maxChecks || results.size() >= INTERPRETER_MAX_RESULTS) break;
                if (valueOrDefault(candidate).truth.confidence() < TRUTH_MIN_CONFIDENCE_MATCH) continue;

                unification.unify(pattern, candidate, Bind.EMPTY)
                        .ifPresent(bind -> {
                            boost(candidate, IMPORTANCE_BOOST_ON_ACCESS);
                            results.add(new Answer(candidate, bind));
                        });
            }
            return results;
        }

        /**
         * Decays importance and removes low-importance atoms.
         */
        synchronized void decayAndForget() {
            final long now = timeSource.get();
            var initialSize = storage.size();
            if (initialSize == 0) return;

            List<Atom> toRemove = new ArrayList<>();
            var decayCount = 0;

            // 1. Decay and identify candidates in one pass
            for (var entry : storage.entrySet()) {
                var atom = entry.getKey();
                var valueRef = entry.getValue();
                var decayedValue = valueRef.updateAndGet(v -> v.decay(now)); // Atomically decay
                decayCount++;
                if (decayedValue.getCurrentImportance(now) < IMPORTANCE_MIN_FORGET_THRESHOLD) {
                    var isProtected = atom instanceof Sym(String name) && PROTECTED_SYMBOLS.contains(name) || atom instanceof Var;
                    if (!isProtected) toRemove.add(atom);
                }
            }

            var removedCount = 0;
            // 2. Forget if memory pressure or many low-importance candidates found
            var targetSize = FORGETTING_MAX_MEM_SIZE_TRIGGER * FORGETTING_TARGET_MEM_SIZE_FACTOR / 100;
            var memoryPressure = initialSize > FORGETTING_MAX_MEM_SIZE_TRIGGER;

            if (!toRemove.isEmpty() && (memoryPressure || initialSize > targetSize * 1.1)) {
                toRemove.sort(Comparator.comparingDouble(atom -> valueOrDefault(atom).getCurrentImportance(now)));
                var removalTargetCount = memoryPressure ? Math.max(0, initialSize - targetSize) : toRemove.size();
                var actuallyRemoved = 0;
                for (var atomToRemove : toRemove) {
                    if (actuallyRemoved >= removalTargetCount) break;
                    if (valueOrDefault(atomToRemove).getCurrentImportance(now) < IMPORTANCE_MIN_FORGET_THRESHOLD) { // Re-check threshold
                        if (removeAtomInternal(atomToRemove)) actuallyRemoved++;
                    }
                }
                removedCount = actuallyRemoved;
            }

            if (removedCount > 0 || decayCount > 0) {
                System.out.printf("Atomspace Maintenance: Decayed %d, Removed %d. Size %d -> %d.%n", decayCount, removedCount, initialSize, storage.size());
            }
        }

        private boolean removeAtomInternal(Atom atom) {
            if (storage.remove(atom) != null) {
                atomsById.remove(atom.id());
                removeIndices(atom);
                return true;
            }
            return false;
        }

        private void checkMemoryAndTriggerForgetting() {
            if (storage.size() > FORGETTING_MAX_MEM_SIZE_TRIGGER) CompletableFuture.runAsync(this::decayAndForget);
        }

        private void updateIndices(Atom atom) {
            if (atom instanceof Expr e && e.head() != null)
                indexByHead.computeIfAbsent(e.head(), h -> new ConcurrentSkipListSet<>()).add(atom.id());
        }

        private void removeIndices(Atom atom) {
            if (atom instanceof Expr e && e.head() != null) indexByHead.computeIfPresent(e.head(), (k, v) -> {
                v.remove(atom.id());
                return v.isEmpty() ? null : v;
            });
        }

        public int size() {
            return storage.size();
        }
    }

    /**
     * Represents variable bindings. Immutable.
     */
    public record Bind(Map<Var, Atom> map) {
        public static final Bind EMPTY = new Bind(emptyMap());

        public Bind {
            map = Map.copyOf(map);
        } // Ensure immutable map

        public boolean isEmpty() {
            return map.isEmpty();
        }

        public Optional<Atom> get(Var var) {
            return Optional.ofNullable(map.get(var));
        }

        /**
         * Recursively resolves bindings for a variable.
         */
        public Optional<Atom> getRecursive(Var var) {
            var current = map.get(var);
            if (current == null) return Optional.empty();
            Set<Var> visited = new HashSet<>();
            visited.add(var);
            while (current instanceof Var v) {
                if (!visited.add(v)) return Optional.empty(); // Cycle detected
                var next = map.get(v);
                if (next == null) return Optional.of(v);
                current = next;
            }
            return Optional.of(current); // Return final concrete value
        }

        @Override
        public String toString() {
            return map.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", ", "{", "}"));
        }
    }

    /**
     * Result of a successful pattern match query.
     */
    public record Answer(Atom resultAtom, Bind bind) {
    }


    // === MeTTa Interpreter =================================================


    /**
     * Performs unification between Atoms using an iterative, stack-based approach.
     */
    public static class Unify {
        private final Memory space; // May be needed for type checks in future

        public Unify(Memory space) {
            this.space = space;
        }

        /**
         * Unifies pattern and instance, returning bindings if successful.
         */
        public Optional<Bind> unify(Atom pattern, Atom instance, Bind initialBind) {
            Deque<Pair<Atom, Atom>> stack = new ArrayDeque<>();
            stack.push(new Pair<>(pattern, instance));
            var currentBindings = initialBind;

            while (!stack.isEmpty()) {
                var task = stack.pop();
                var p = substitute(task.a(), currentBindings); // Apply current bindings
                var i = substitute(task.b(), currentBindings);

                if (p.equals(i)) continue; // Identical, proceed

                if (p instanceof Var pVar) {
                    if (containsVariable(i, pVar)) return Optional.empty();
                    currentBindings = mergeBinding(currentBindings, pVar, i);
                    if (currentBindings == null) return Optional.empty();
                    continue;
                }
                if (i instanceof Var iVar) {
                    if (containsVariable(p, iVar)) return Optional.empty();
                    currentBindings = mergeBinding(currentBindings, iVar, p);
                    if (currentBindings == null) return Optional.empty();
                    continue;
                }

                if (p.type() != i.type()) return Optional.empty();
                if (p instanceof Expr pExpr && i instanceof Expr iExpr) {
                    var pChildren = pExpr.children();
                    var iChildren = iExpr.children();
                    var ps = pChildren.size();
                    if (ps != iChildren.size()) return Optional.empty();
                    for (var j = 0; j < ps; j++)
                        stack.push(new Pair<>(pChildren.get(j), iChildren.get(j)));

                } else {
                    return Optional.empty();
                } // Mismatch
            }
            return Optional.of(currentBindings);
        }

        /**
         * Applies bindings to an Atom, replacing variables recursively.
         */
        public Atom substitute(Atom atom, Bind bind) {
            if (bind.isEmpty()) return atom;
            return switch (atom) {
                case Var var -> bind.getRecursive(var).map(val -> substitute(val, bind)).orElse(var);
                case Expr expr -> {
                    var changed = false;
                    List<Atom> newChildren = new ArrayList<>(expr.children().size());
                    for (var child : expr.children()) {
                        var sub = substitute(child, bind);
                        if (child != sub) changed = true;
                        newChildren.add(sub);
                    }
                    yield changed ? new Expr(newChildren) : expr;
                }
                default -> atom;
            };
        }

        private boolean containsVariable(Atom expr, Var var) {
            return switch (expr) {
                case Var v -> v.equals(var);
                case Expr e -> e.children().stream().anyMatch(c -> containsVariable(c, var));
                default -> false;
            };
        }

        private @Nullable Cog.Bind mergeBinding(Bind current, Var var, Atom value) {
            var existingOpt = current.getRecursive(var);
            if (existingOpt.isPresent()) return unify(existingOpt.get(), value, current).orElse(null);
            Map<Var, Atom> newMap = new HashMap<>(current.map());
            newMap.put(var, value);
            return new Bind(newMap);
        }
    }


    // === Agent & Environment ==============================================


    /**
     * action result
     */
    public record Act(List<Atom> newPercepts, double reward) {
    }

    static class SimpleGame implements Game {
        private final Sym posA, posB, posGoal, moveAtoB, moveBtoGoal, moveOther, statePred, selfSym;
        private Atom currentStateSymbol;

        SimpleGame(Cog cog) {
            posA = cog.sym("Pos_A");
            posB = cog.sym("Pos_B");
            posGoal = cog.sym("AtGoal");
            moveAtoB = cog.sym("Move_A_B");
            moveBtoGoal = cog.sym("Move_B_Goal");
            moveOther = cog.sym("Move_Other");
            statePred = cog.sym("State");
            selfSym = cog.sym("Self");
            currentStateSymbol = posA;
        }

        @Override
        public List<Atom> perceive(Cog cog) {
            return List.of(cog.E(statePred, selfSym, currentStateSymbol));
        }

        @Override
        public List<Atom> actions(Cog cog, Atom currentState) {
            if (currentStateSymbol.equals(posA)) return List.of(moveAtoB, moveOther);
            if (currentStateSymbol.equals(posB)) return List.of(moveBtoGoal, moveOther);
            return List.of(moveOther);
        }

        @Override
        public Act exe(Cog cog, Atom actionSymbol) {
            var reward = -0.1;
            if (currentStateSymbol.equals(posA) && actionSymbol.equals(moveAtoB)) {
                currentStateSymbol = posB;
                reward = 0.1;
                System.out.println("Env: Moved A -> B");
            } else if (currentStateSymbol.equals(posB) && actionSymbol.equals(moveBtoGoal)) {
                currentStateSymbol = posGoal;
                reward = 1.0;
                System.out.println("Env: Moved B -> Goal!");
            } else {
                reward = -0.5;
                System.out.println("Env: Invalid/Other action: " + actionSymbol.id());
            }
            return new Act(perceive(cog), reward);
        }

        @Override
        public boolean isRunning() {
            return !currentStateSymbol.equals(posGoal);
        }
    }

    // --- Utility Classes ---
    private record Pair<A, B>(A a, B b) {
    } // Simple pair for internal use


    // === Environment Example ==============================================


    /**
     * Evaluates MeTTa expressions using unification against equality rules.
     */
    public class Interp {
        private final Unify unification;
        private final Sym SYMBOL_EQ;

        public Interp(Memory space, Unify unify) {
            this.unification = unify;
            this.SYMBOL_EQ = sym("=");
        }

        public Atom substitute(Atom atom, Bind bind) {
            return unification.substitute(atom, bind);
        }

        /**
         * Evaluates an Atom with a specified maximum recursion depth.
         */
        public List<Atom> eval(Atom atom, int maxDepth) {
            Set<String> visited = new HashSet<>();
            var results = eval(atom, maxDepth, visited);
            return results.isEmpty() ? List.of(atom) : results.stream().distinct().limit(INTERPRETER_MAX_RESULTS).toList();
        }

        /**
         * evaluates recursively
         */
        private List<Atom> eval(Atom atom, int depth, Set<String> visitedInPath) {
            if (depth <= 0 || !visitedInPath.add(atom.id())) return List.of(atom); // Depth limit or cycle

            List<Atom> results = new ArrayList<>();
            try {
                switch (atom) {
                    case Sym s -> results.add(s);
                    case Var v -> results.add(v);
                    case Is<?> ga when !ga.isExecutable() -> results.add(ga);
                    case Expr expr -> {
                        // 1. Try specific rules: (= <expr> $result)
                        var resultVar = V("evalRes" + depth);
                        Atom query = E(SYMBOL_EQ, expr, resultVar);
                        var matches = Cog.this.space.query(query);
                        if (!matches.isEmpty()) {
                            matches.forEach(m -> m.bind.get(resultVar).ifPresent(t -> results.addAll(eval(t, depth - 1, new HashSet<>(visitedInPath)))));
                            if (results.size() >= INTERPRETER_MAX_RESULTS) break; // Limit early
                        }

                        // 2. Try general rules: (= <pattern> <template>)
                        if (results.isEmpty()) {
                            var pVar = V("p" + depth);
                            var tVar = V("t" + depth);
                            Atom genQuery = E(SYMBOL_EQ, pVar, tVar);
                            var ruleMatches = Cog.this.space.query(genQuery);
                            for (var ruleMatch : ruleMatches) {
                                var pattern = ruleMatch.bind.get(pVar).orElse(null);
                                var template = ruleMatch.bind.get(tVar).orElse(null);
                                if (pattern == null || template == null || pattern.equals(expr)) continue;
                                unification.unify(pattern, expr, Bind.EMPTY).ifPresent(exprBind ->
                                        results.addAll(eval(substitute(template, exprBind), depth - 1, new HashSet<>(visitedInPath)))
                                );
                                if (results.size() >= INTERPRETER_MAX_RESULTS) break;
                            }
                        }

                        // 3. Try executing head if Grounded Function (applicative order)
                        if (results.isEmpty() && expr.head() instanceof Is<?> ga && ga.isExecutable()) {
                            List<Atom> evaluatedArgs = new ArrayList<>();
                            var argEvalOk = true;
                            for (var arg : expr.tail()) { // Evaluate args - simplified: use first result only
                                var argRes = eval(arg, depth - 1, new HashSet<>(visitedInPath));
                                if (argRes.isEmpty() || argRes.size() > 1 && argRes.stream().distinct().count() > 1) {
                                    argEvalOk = false;
                                    break;
                                } // Fail if no result or ambiguous
                                evaluatedArgs.add(argRes.isEmpty() ? arg : argRes.getFirst()); // Use original if no eval result
                            }
                            if (argEvalOk)
                                ga.execute(evaluatedArgs).ifPresent(res -> results.addAll(eval(res, depth - 1, new HashSet<>(visitedInPath))));
                        }

                        // 4. No rule/execution applied -> evaluate to self
                        if (results.isEmpty()) results.add(atom);
                    }
                    case Is<?> ga -> {
                        if (!ga.isExecutable()) results.add(ga);
                    } // Should be handled by above but safe fallback
                }
            } finally {
                visitedInPath.remove(atom.id());
            } // Backtrack visited set
            return results;
        }
    }

    /**
     * Agent implementing perceive-evaluate-act-learn cycle using MeTTa.
     */
    public class Agent {
        private final RandomGenerator random = RandomGenerator.getDefault();
        private final Sym STATE_PRED, GOAL_PRED, ACTION_PRED, UTILITY_PRED, IMPLIES_PRED, SEQ_PRED;
        private @Nullable Atom currentState = null; // Often a symbolic representation of state

        public Agent(Cog cog) {
            this.STATE_PRED = cog.sym("State");
            this.GOAL_PRED = cog.sym("Goal");
            this.ACTION_PRED = cog.sym("Action");
            this.UTILITY_PRED = cog.sym("Utility");
            this.IMPLIES_PRED = cog.sym("Implies");
            this.SEQ_PRED = cog.sym("Seq");
        }

        public void run(Game env, Atom goalPattern, int maxCycles) {
            System.out.println("\n--- Agent Run Start ---");
            initializeGoal(goalPattern);
            currentState = processPerception(env.perceive(Cog.this));

            for (var cycle = 0; cycle < maxCycles && env.isRunning(); cycle++) {
                var time = Cog.this.tick();
                System.out.printf("\n--- Agent Cycle %d (Time: %d) ---%n", cycle + 1, time);
                System.out.println("Current State: " + currentState);
                if (isGoalAchieved(goalPattern)) {
                    System.out.println("*** Agent: Goal Achieved! ***");
                    break;
                }

                var actionOpt = selectAction(env.actions(Cog.this, currentState));
                if (actionOpt.isPresent()) {
                    var action = actionOpt.get();
                    System.out.println("Agent: Selected Action: " + action);
                    var result = env.exe(Cog.this, action); // Execute the action symbol/structure
                    var nextState = processPerception(result.newPercepts);
                    learn(currentState, action, nextState, result.reward);
                    currentState = nextState;
                } else {
                    System.out.println("Agent: No action selected. Idling.");
                }
            }
            System.out.println("--- Agent Run Finished ---");
        }

        private void initializeGoal(Atom goalPattern) {
            Atom goalAtom = Cog.this.space.add(E(GOAL_PRED, goalPattern));
            Cog.this.space.boost(goalAtom, IMPORTANCE_BOOST_ON_GOAL_FOCUS);
            Cog.this.space.boost(goalPattern, IMPORTANCE_BOOST_ON_GOAL_FOCUS * 0.8);
            System.out.println("Agent: Goal initialized -> " + goalAtom);
        }

        private boolean isGoalAchieved(Atom goalPattern) {
            var achieved = !Cog.this.space.query(goalPattern).isEmpty();
            if (achieved) System.out.println("Agent: Goal check query successful.");
            return achieved;
        }

        private Atom processPerception(List<Atom> percepts) {
            List<String> factIDs = Collections.synchronizedList(new ArrayList<>());
            percepts.forEach(p -> {
                var atom = Cog.this.space.add(p);
                Cog.this.space.updateTruth(atom, new Truth(1.0, AGENT_DEFAULT_PERCEPTION_COUNT));
                Cog.this.space.boost(atom, IMPORTANCE_BOOST_ON_PERCEPTION);
                factIDs.add(atom.id());
            });
            Collections.sort(factIDs);
            var hash = String.valueOf(String.join("|", factIDs).hashCode());
            var stateAtom = Cog.this.sym("State_" + hash);
            Cog.this.space.updateTruth(stateAtom, Truth.TRUE);
            Cog.this.space.boost(stateAtom, IMPORTANCE_BOOST_ON_PERCEPTION);
            return stateAtom;
        }

        private Optional<Atom> selectAction(List<Atom> availableActions) {
            if (availableActions.isEmpty()) return Optional.empty();
            // Simplified: Choose reactively based on utility, then explore
            Map<Atom, Double> utilities = new ConcurrentHashMap<>();
            var valVar = V("val");
            availableActions.parallelStream().forEach(action -> {
                var atom = Cog.this.space.add(action);
                Atom utilExpr = E(UTILITY_PRED, atom);
                var util = Cog.this.interp.eval(utilExpr, 3).stream().filter(a -> a instanceof Is<?> g && g.value() instanceof Number).mapToDouble(a -> ((Number) ((Is<?>) a).value()).doubleValue()).max().orElse(0.0);
                if (util > AGENT_UTILITY_THRESHOLD) utilities.put(atom, util);
            });
            var best = utilities.entrySet().stream().max(Map.Entry.comparingByValue());
            if (best.isPresent()) {
                System.out.printf("Agent: Selecting by utility: %s (Util: %.3f)%n", best.get().getKey(), best.get().getValue());
                return Optional.of(best.get().getKey());
            }
            if (random.nextDouble() < AGENT_RANDOM_ACTION_PROBABILITY) {
                System.out.println("Agent: Selecting random action (exploration).");
                return Optional.of(Cog.this.space.add(availableActions.get(random.nextInt(availableActions.size()))));
            }
            System.out.println("Agent: No high-utility action found, selecting random fallback.");
            return Optional.of(Cog.this.space.add(availableActions.get(random.nextInt(availableActions.size()))));
        }

        private void learn(Atom prevState, Atom action, Atom nextState, double reward) {
            if (prevState == null || action == null) return;
            // 1. Learn Transition: (Implies (Seq <prevState> <action>) <nextState>)
            Atom seq = Cog.this.space.add(E(SEQ_PRED, prevState, action));
            Atom imp = Cog.this.space.add(E(IMPLIES_PRED, seq, nextState));
            Cog.this.space.updateTruth(imp, new Truth(1.0, AGENT_LEARNED_RULE_COUNT));
            Cog.this.space.boost(imp, IMPORTANCE_BOOST_ON_ACCESS * 1.5);
            // 2. Learn Utility: (= (Utility <action>) <value>)
            Atom utilExpr = E(UTILITY_PRED, action);
            var currentValVar = V("curVal");
            Atom query = E(SYMBOL_EQ, utilExpr, currentValVar);
            var currentUtil = Cog.this.space.query(query).stream().map(qr -> qr.bind.get(currentValVar)).filter(Optional::isPresent).map(Optional::get).filter(a -> a instanceof Is<?> g && g.value() instanceof Number).mapToDouble(a -> ((Number) ((Is<?>) a).value()).doubleValue()).findFirst().orElse(0.0);
            var learningRate = 0.2;
            var newUtility = currentUtil + learningRate * (reward - currentUtil);
            Atom newValAtom = Cog.this.G(newUtility); // Create grounded number
            Atom newRule = Cog.this.space.add(E(SYMBOL_EQ, utilExpr, newValAtom));
            Cog.this.space.updateTruth(newRule, Truth.TRUE);
            Cog.this.space.boost(newRule, IMPORTANCE_BOOST_ON_ACCESS * 1.5);
            System.out.printf("  Learn: Updated utility for %s to %.3f%n", action.id(), newUtility);
        }
    }

    /**
     * bootstrap logic
     */
    private class Core {
        private Core() {
            Cog.SYMBOL_EQ = sym("=");
            Cog.SYMBOL_COLON = sym(":");
            Cog.SYMBOL_ARROW = sym("->");
            Cog.SYMBOL_TYPE = sym("Type");
            Cog.SYMBOL_TRUE = sym("True");
            Cog.SYMBOL_FALSE = sym("False");
            Cog.SYMBOL_SELF = sym("Self");
            Cog.SYMBOL_NIL = sym("Nil");
            space.updateTruth(SYMBOL_TRUE, Truth.TRUE);
            space.updateTruth(SYMBOL_FALSE, Truth.FALSE);
            TYPE(SYMBOL_EQ, SYMBOL_TYPE);
            TYPE(SYMBOL_COLON, SYMBOL_TYPE);
            TYPE(SYMBOL_ARROW, SYMBOL_TYPE);
            TYPE(SYMBOL_TYPE, SYMBOL_TYPE);
            TYPE(SYMBOL_TRUE, SYMBOL_TYPE);
            TYPE(SYMBOL_FALSE, SYMBOL_TYPE);

            var NUMBER = sym("Number");
            TYPE(NUMBER, SYMBOL_TYPE);

            var BOOL = sym("Bool");
            TYPE(BOOL, SYMBOL_TYPE);
            TYPE(SYMBOL_TRUE, BOOL);
            TYPE(SYMBOL_FALSE, BOOL);
            G("match", args -> {
                if (args.size() != 3) return Optional.empty();
                var target = args.get(0) instanceof Is<?> g && g.value() instanceof Memory ts ? ts : space;
                var qr = target.query(args.get(1));
                var res = qr.stream().map(r -> interp.substitute(args.get(2), r.bind)).toList();
                return Optional.of(G(res));
            });
            G("eval", args -> args.isEmpty() ? Optional.empty() : evalBest(args.getFirst()));
            G("add-atom", args -> args.isEmpty() ? Optional.empty() : Optional.of(add(args.getFirst())));
            G("remove-atom", args -> {
                var rem = !args.isEmpty() && space.removeAtomInternal(args.getFirst());
                return Optional.of(rem ? SYMBOL_TRUE : SYMBOL_FALSE);
            });
            G("get-value", args -> args.isEmpty() ? Optional.empty() : Optional.of(G(space.value(args.getFirst()))));
            G("If", args -> {
                if (args.size() != 3) return Optional.empty();
                var condRes = eval(args.get(0));
                return Optional.of(condRes.contains(SYMBOL_TRUE) ? args.get(1) : args.get(2));
            });

            var cog = Cog.this;
            G("Grounded+", args -> applyNumericOp(args, cog, Double::sum));
            G("Grounded-", args -> applyNumericOp(args, cog, (a, b) -> a - b));
            G("Grounded*", args -> applyNumericOp(args, cog, (a, b) -> a * b));
            G("Grounded/", args -> applyNumericOp(args, cog, (a, b) -> b == 0 ? Double.NaN : a / b));
            G("Grounded==", args -> applyNumericComp(args, cog, (a, b) -> Math.abs(a - b) < 1e-9));
            G("Grounded>", args -> applyNumericComp(args, cog, (a, b) -> a > b));
            G("Grounded<", args -> applyNumericComp(args, cog, (a, b) -> a < b));
            var A = V("a");
            var B = V("b");
            EQ(E(S("+"), A, B), E(S("Grounded+"), A, B));
            EQ(E(S("-"), A, B), E(S("Grounded-"), A, B));
            EQ(E(S("*"), A, B), E(S("Grounded*"), A, B));
            EQ(E(S("/"), A, B), E(S("Grounded/"), A, B));
            EQ(E(S("=="), A, B), E(S("Grounded=="), A, B));
            EQ(E(S(">"), A, B), E(S("Grounded>"), A, B));
            EQ(E(S("<"), A, B), E(S("Grounded<"), A, B));
            EQ(E(S("If"), SYMBOL_TRUE, A, B), A);
            EQ(E(S("If"), SYMBOL_FALSE, A, B), B); // If rules

            G("&self", args -> {
                return Optional.empty(); //TODO?
            }); // Grounded reference to the space itself
        }

        private static Optional<Atom> applyNumericOp(List<Atom> args, Cog cog, BinaryOperator<Double> op) {
            var n = args.size();
            var n1 = getNumericValue(n > 0 ? args.get(0) : null, cog);
            var n2 = getNumericValue(n > 1 ? args.get(1) : null, cog);
            return n1.isPresent() && n2.isPresent() ? Optional.of(cog.G(op.apply(n1.get(), n2.get()))) : Optional.empty();
        }

        private static Optional<Atom> applyNumericComp(List<Atom> args, Cog cog, BiPredicate<Double, Double> op) {
            var n = args.size();
            var n1 = getNumericValue(n > 0 ? args.get(0) : null, cog);
            var n2 = getNumericValue(n > 1 ? args.get(1) : null, cog);
            return n1.isPresent() && n2.isPresent() ? Optional.of(op.test(n1.get(), n2.get()) ? SYMBOL_TRUE : SYMBOL_FALSE) : Optional.empty();
        }

        private static Optional<Double> getNumericValue(@Nullable Atom atom, Cog cog) {
            if (atom == null) return Optional.empty();
            var target = atom;
            if (!(atom instanceof Is<?> g && g.value() instanceof Number)) target = cog.evalBest(atom).orElse(atom);
            return target instanceof Is<?> g && g.value() instanceof Number n ? Optional.of(n.doubleValue()) : Optional.empty();
        }
    }
}