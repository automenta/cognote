package dumb.hyp2;

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
 * - **Is Atoms:** {@link Is} bridges to Java code/data for I/O, math, environment interaction, etc.
 * - **Metadata:** Immutable {@link Value} records (holding {@link Truth}, {@link Pri}, {@link Time}) associated with Atoms via {@link AtomicReference} for atomic updates.
 * - **Probabilistic & Priority Driven:** Truth values handle uncertainty; Pri values (STI/LTI) guide attention and forgetting.
 * - **Agent Framework:** Includes a basic {@link Agent} model demonstrating perception, reasoning (via evaluation), action, and learning (by adding rules).
 * - **Modern Java:** Leverages Records, Sealed Interfaces, Streams, Concurrent Collections, AtomicReference for robustness and conciseness.
 * <p>
 * ## Key Enhancements (Iteration 4):
 * - **MeTTa Parser:** Introduced {@link MettaParser} to parse MeTTa syntax strings into Atoms.
 * - **Bootstrap via MeTTa:** Core rules and symbols are now defined using MeTTa syntax strings, parsed during init.
 * - **Examples via MeTTa:** The `main` method demonstration uses parsed MeTTa strings extensively.
 * - **Consolidated Helpers:** Atom creation helpers (`S`, `V`, `E`, `G`) remain for programmatic use, alongside parser methods (`parse`, `load`).
 * - **Minor Optimizations:** Refined ID caching, index usage, and forgetting logic.
 *
 * @version 4.1 - Parser Integration
 */
public final class Cog {

    private static final double TRUTH_DEFAULT_SENSITIVITY = 1.0; // k in confidence = count / (count + k)
    private static final double TRUTH_MIN_CONFIDENCE_MATCH = 0.01; // Min confidence for an atom to be considered in matching/relevance
    private static final double TRUTH_REVISION_CONFIDENCE_THRESHOLD = 0.1; // Min confidence diff for revision boost

    private static final double PRI_INITIAL_STI = 0.2;
    private static final double PRI_INITIAL_LTI_FACTOR = 0.1;
    private static final double PRI_STI_DECAY_RATE = 0.08; // Decay per maintenance cycle
    private static final double PRI_LTI_DECAY_RATE = 0.008;
    private static final double PRI_STI_TO_LTI_RATE = 0.02;
    private static final double PRI_BOOST_ON_ACCESS = 0.08;
    private static final double PRI_BOOST_ON_REVISION_MAX = 0.5;
    private static final double PRI_BOOST_ON_GOAL_FOCUS = 0.95;
    private static final double PRI_BOOST_ON_PERCEPTION = 0.75;
    private static final double PRI_MIN_FORGET_THRESHOLD = 0.015; // Combined pri threshold
    private static final long FORGETTING_CHECK_INTERVAL_MS = 12000;
    private static final int FORGETTING_MAX_MEM_SIZE_TRIGGER = 18000;
    private static final int FORGETTING_TARGET_MEM_SIZE_FACTOR = 80; // %

    private static final int INTERPRETER_DEFAULT_MAX_DEPTH = 15; // Max recursive evaluation depth
    private static final int INTERPRETER_MAX_RESULTS = 50; // Limit non-deterministic results per evaluation step

    private static final double AGENT_DEFAULT_PERCEPTION_COUNT = 8.0;
    private static final double AGENT_LEARNED_RULE_COUNT = 1.5;
    private static final double AGENT_UTILITY_THRESHOLD = 0.1;
    private static final double AGENT_RANDOM_ACTION_PROBABILITY = 0.05;

    private static final String VAR_PREFIX = "$"; // MeTTa var prefix convention
    private static final Set<String> PROTECTED_SYMBOLS = Set.of( // Symbols protected from forgetting
            "=", ":", "->", "Type", "True", "False", "Nil", "Self", "Goal", "State", "Action",
            "match", "eval", "add-atom", "remove-atom", "get-value", "If", "+", "-", "*", "/", // Core Is ops
            "Number", "Bool", "&self" // Core types and self ref
    );

    // --- Core Symbols (initialized in CoreBootstrap) ---
    public static Atom SYMBOL_EQ, SYMBOL_COLON, SYMBOL_ARROW, SYMBOL_TYPE, SYMBOL_TRUE, SYMBOL_FALSE, SYMBOL_SELF, SYMBOL_NIL; // Represents empty list/result often

    public final Mem mem;
    public final Unify unify;
    public final Agent agent;
    private final Interp interp;
    private final MettaParser parser;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong logicalTime = new AtomicLong(0);

    public Cog() {
        this.mem = new Mem(this::getLogicalTime);
        this.unify = new Unify(this.mem);
        this.interp = new Interp(this); // Pass Cog reference for access to space, etc.
        this.agent = new Agent(this);
        this.parser = new MettaParser(this);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "Cog-Maintenance");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::performMaintenance, FORGETTING_CHECK_INTERVAL_MS, FORGETTING_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);

        new Core(this).initialize();

        System.out.println("Cog (v4.1 Parser Integration) Initialized. Init Size: " + mem.size());
    }

    // --- Main Demonstration ---
    public static void main(String[] args) {
        var cog = new Cog();
        try {
            System.out.println("\n--- Cog Synthesized Test Suite (v4.1) ---");

            // --- [1] Parsing & Atom Basics ---
            printSectionHeader(1, "Parsing & Atom Basics");
            var red = cog.parse("Red");
            var x = cog.parse("$x");
            var knowsSelf = cog.parse("(Knows Self Cog)");
            var numPi = cog.parse("3.14159"); // Parsed as Is<Double>
            var strHello = cog.parse("\"Hello\""); // Parsed as Is<String>
            var boolTrue = cog.parse("True"); // Parsed as Symbol("True")

            cog.add(red);
            cog.add(x);
            cog.add(knowsSelf);
            cog.add(numPi);
            cog.add(strHello);

            System.out.println("Parsed Atoms: " + red + ", " + x + ", " + knowsSelf + ", " + numPi + ", " + strHello + ", " + boolTrue);
            System.out.println("Retrieved Cog Atom: " + cog.mem.getAtom(knowsSelf));
            System.out.println("Retrieved Pi Atom by ID: " + cog.mem.getAtom(numPi.id()));
            System.out.println("Default Value for Red: " + cog.mem.value(red));

            cog.mem.updateTruth(red, new Truth(0.9, 5.0));
            cog.mem.boost(red, 0.5);
            System.out.println("Updated Value for Red: " + cog.mem.value(red));

            cog.mem.updateTruth(red, new Truth(0.95, 15.0)); // Merge truth
            System.out.println("Merged Value for Red: " + cog.mem.value(red));

            // --- [2] Unification ---
            printSectionHeader(2, "Unification");
            var knows = cog.S("Knows"); // Programmatic creation still useful
            var sam = cog.S("A");
            var sth = cog.S("Something");
            var fact1 = cog.parse("(Knows A Something)");
            var pattern1 = cog.parse("(Knows A $w)");
            var pattern2 = cog.parse("(Knows $p Something)");
            var pattern3 = cog.parse("(Knows $p $w)");
            var patternFail = cog.parse("(Forgets A Something)");

            cog.add(fact1); // Add the fact to the space

            testUnification(cog, "Simple Match", pattern1, fact1);
            testUnification(cog, "Var Match 1", pattern2, fact1);
            testUnification(cog, "Var Match 2", pattern3, fact1);
            testUnification(cog, "Mismatch", patternFail, fact1);
            testUnification(cog, "Occurs Check Fail", x, cog.parse("(Knows $x)"));

            // --- [3] MeTTa Evaluation - Simple Rules (Defined via Parser in Bootstrap) ---
            printSectionHeader(3, "MeTTa Evaluation - Simple Rules (Peano)");
            // Rules for 'add' are now loaded during init from MeTTa string
            // (= (add Z $n) $n)
            // (= (add (S $m) $n) (S (add $m $n)))
            var exprAdd1 = cog.parse("(add (S Z) (S Z))");
            var exprAdd2 = cog.parse("(add (S (S Z)) (S Z))");

            evaluateAndPrint(cog, "Peano Add 1+1", exprAdd1); // Expect (S (S Z))
            evaluateAndPrint(cog, "Peano Add 2+1", exprAdd2); // Expect (S (S (S Z)))

            // --- [4] MeTTa Evaluation - Is Atoms & Control Flow (Defined via Parser in Bootstrap) ---
            printSectionHeader(4, "MeTTa Evaluation - Is Atoms & Control Flow");
            // Rules like (= (+ $a $b) (_+ $a $b)) are in init
            var exprArith1 = cog.parse("(+ 5.0 3.0)");
            evaluateAndPrint(cog, "Arithmetic 5+3", exprArith1); // Expect Is<Double:8.0>

            var exprArith2 = cog.parse("(* (+ 2.0 3.0) 4.0)"); // (* (+ 2 3) 4)
            evaluateAndPrint(cog, "Arithmetic (2+3)*4", exprArith2); // Expect Is<Double:20.0>

            var exprComp = cog.parse("(> 5.0 3.0)");
            evaluateAndPrint(cog, "Comparison 5 > 3", exprComp); // Expect True

            // If rules are in init: (= (If True $then $else) $then), (= (If False $then $else) $else)
            var exprIfTrue = cog.parse("(If True ResultA ResultB)");
            evaluateAndPrint(cog, "If True", exprIfTrue); // Expect ResultA

            var exprIfCond = cog.parse("(If (> 5.0 3.0) FiveIsGreater ThreeIsGreater)");
            evaluateAndPrint(cog, "If (5 > 3)", exprIfCond); // Expect FiveIsGreater

            // --- [5] Pattern Matching Query (`match`) ---
            printSectionHeader(5, "Pattern Matching Query");
            cog.load("""
                    (Knows A Something)
                    (Knows B Something)
                    (Knows A OtherThing)
                    """);

            var querySomething = cog.parse("(Knows $p Something)");
            System.out.println("Querying: " + querySomething);
            var pizzaResults = cog.query(querySomething);
            printQueryResults(pizzaResults); // Expect matches for A and B

            // Query using the 'match' Is function
            var matchExpr = cog.parse("(match &self (Knows $p Something) $p)"); // Match in default space, return $p
            evaluateAndPrint(cog, "Is<List:[A,B]> Match Query", matchExpr); // Expect Is<List:[A, B]>

            // --- [6] Truth & Importance Values ---
            printSectionHeader(6, "Truth & Pri Values");
            cog.load("(: Penguin Bird)");
            var penguinFlies = cog.add(cog.parse("(Flies Penguin)"));

            cog.mem.updateTruth(penguinFlies, new Truth(0.1, 20.0)); // Penguins likely don't fly
            cog.mem.boost(penguinFlies, 0.7);
            System.out.println("Penguin Flies Value: " + cog.mem.value(penguinFlies));

            var birdFlies = cog.add(cog.parse("(Flies Bird)")); // Generic bird likely flies
            cog.mem.updateTruth(birdFlies, new Truth(0.9, 15.0));
            System.out.println("Bird Flies Value: " + cog.mem.value(birdFlies));

            // --- [7] Forgetting ---
            printSectionHeader(7, "Forgetting");
            var protectedAtom = cog.S("ImportantConcept"); // Still useful to get ref programmatically
            cog.add(protectedAtom); // Ensure it exists
            cog.mem.boost(protectedAtom, 1.0); // Make very important initially
            System.out.println("Protected atom: " + protectedAtom + ", Initial Value: " + cog.mem.value(protectedAtom));

            System.out.println("Adding 100 low-pri temporary atoms...");
            for (var i = 0; i < 100; i++) {
                var temp = cog.S("Temp_" + i + "_" + UUID.randomUUID().toString().substring(0, 4)); // Unique name
                cog.add(temp);
                cog.mem.updateValue(temp, v -> v.withPri(new Pri(0.001, 0.0001))); // Very low pri
            }
            System.out.println("Memory size before wait: " + cog.mem.size());
            System.out.println("Waiting for maintenance cycle (approx " + FORGETTING_CHECK_INTERVAL_MS / 1000 + "s)...");
            Thread.sleep(FORGETTING_CHECK_INTERVAL_MS + 2000); // Wait longer than interval

            var sizeAfter = cog.mem.size();
            System.out.println("Memory size after wait: " + sizeAfter);
            var protectedCheck = cog.mem.getAtom(protectedAtom); // Check if protected atom still exists
            System.out.println("Protected atom '" + protectedAtom/*.name()*/ + "' still exists: " + protectedCheck.isPresent());
            System.out.println("Protected atom value after wait: " + protectedCheck.flatMap(cog.mem::value)); // Pri should have decayed slightly

            // --- [8] Agent Simulation ---
            printSectionHeader(8, "Agent Simulation");
            var env = new SimpleGame(cog);
            var agentGoal = cog.parse("(State Self AtGoal)"); // Goal defined via MeTTa
            cog.runAgent(env, agentGoal, 10); // Run for a few steps

            // Query learned utilities after agent run
            System.out.println("\nQuerying learned utilities:");
            var utilQuery = cog.parse("(Utility $action)");
            var utilResults = cog.query(utilQuery); // Query the utility values directly
            printQueryResults(utilResults);

            // --- [9] Metaprogramming (Adding rules via MeTTa 'add-atom') ---
            printSectionHeader(9, "Metaprogramming");
            // Define the rule to add as a MeTTa string
            var ruleToAddString = "(= (NewPredicate ConceptA) ResultValue)";
            var ruleToAddAtom = cog.parse(ruleToAddString); // Parse the rule

            // Use the Is 'add-atom' function to add the rule via evaluation
            // Need to quote the atom to prevent it from being evaluated before add-atom call
            // Option 1: Add programmatically (simpler for this case)
            // var addRuleExpr = cog.E(cog.S("add-atom"), ruleToAddAtom);
            // Option 2: Try parsing an expression containing the rule atom (needs careful quoting or representation)
            // Let's use the programmatic approach for clarity here.
            // If we wanted to parse it, it might look like: (add-atom '(= (NewPredicate ConceptA) ResultValue))
            // But we don't have a standard quote mechanism yet in the parser/interpreter.
            var addAtomSym = cog.S("add-atom");
            var addRuleExpr = cog.E(addAtomSym, ruleToAddAtom); // Create expression programmatically

            System.out.println("Evaluating meta-expression to add rule: " + addRuleExpr);
            evaluateAndPrint(cog, "Add Rule Meta", addRuleExpr); // Should return the added rule Atom

            // Verify the rule works by evaluating the new predicate
            var testExpr = cog.parse("(NewPredicate ConceptA)");
            System.out.println("\nEvaluating new predicate after meta-add: " + testExpr);
            evaluateAndPrint(cog, "Test New Rule", testExpr); // Expect [ResultValue]

            // --- [10] Loading Multiple Atoms ---
            printSectionHeader(10, "Loading Multiple Atoms");
            var multiAtomData = """
                    ; Some comments
                    (fact One)
                    (: Two Number) ; Type declaration
                    (= (greet $name) (Concat "Hello, " $name "!"))
                    """;
            System.out.println("Loading MeTTa block:\n" + multiAtomData);
            var loaded = cog.load(multiAtomData);
            System.out.println("Loaded " + loaded.size() + " atoms:");
            loaded.forEach(a -> System.out.println(" - " + a + " | Value: " + cog.mem.value(a)));
            System.out.println("Querying (fact $x):");
            printQueryResults(cog.query(cog.parse("(fact $x)")));

        } catch (Exception e) {
            System.err.println("\n--- ERROR during demonstration ---");
            e.printStackTrace();
        } finally {
            cog.shutdown();
        }
        System.out.println("\n--- Cog Synthesized Test Suite Finished ---");
    }

    // --- Helper Methods for Testing/Demo ---
    private static void printSectionHeader(int sectionNum, String title) {
        System.out.printf("\n--- [%d] %s ---\n", sectionNum, title);
    }

    private static void testUnification(Cog cog, String testName, Atom pattern, Atom instance) {
        System.out.print("Unify (" + testName + "): " + pattern + " with " + instance + " -> ");
        var result = cog.unify.unify(pattern, instance, Bind.EMPTY);
        System.out.println(result.isPresent() ? "Success: " + result.get() : "Failure");
    }

    private static void evaluateAndPrint(Cog cog, String testName, Atom expression) {
        System.out.println("eval \"" + testName + "\" \t " + expression);
        var results = cog.eval(expression); // Use default depth
        System.out.print(" -> [");
        System.out.print(results.stream().map(Atom::toString).collect(Collectors.joining(", ")));
        System.out.println("]");
        // Optionally print truth/pri
        // results.forEach(r -> System.out.println("      Value: " + cog.space.value(r)));
    }

    private static void printQueryResults(List<Answer> results) {
        if (results.isEmpty()) {
            System.out.println(" -> No matches found.");
        } else {
            results.forEach(qr -> System.out.println(" -> Match: " + qr.resultAtom() + " | Binds: " + qr.bind));
        }
    }

    /**
     * Parses a single MeTTa expression string into an Atom.
     */
    public Atom parse(String metta) {
        return parser.parse(metta);
    }

    /**
     * Parses a string containing multiple MeTTa expressions (separated by whitespace/newlines) and adds them to the Memory. Comments (starting with ';') are ignored.
     */
    public List<Atom> load(String mettaCode) {
        return parser.parseAll(mettaCode).stream()
                .map(this::add) // Add each parsed atom to the space
                .toList();
    }

    /**
     * Creates/retrieves a Symbol Atom.
     */
    public Atom S(String name) {
        return mem.sym(name);
    }

    /**
     * Creates/retrieves a Var
     */
    public Var V(String name) {
        return mem.var(name);
    }

    /**
     * Creates/retrieves an Expression Atom.
     */
    public Expr E(Atom... children) {
        return mem.expr(List.of(children));
    }

    /**
     * Creates/retrieves an Expression Atom.
     */
    public Expr E(List<Atom> children) {
        return mem.expr(children);
    }

    /**
     * Creates/retrieves a Is Atom wrapping a non-Fn Java value.
     */
    public <T> Is<T> IS(T value) {
        return mem.is(value);
    }

    /**
     * Creates/retrieves a Is "Fn" (function) Atom that wraps provided Java lambda, identified by name.
     */
    public Is<Function<List<Atom>, Optional<Atom>>> IS(String name, Function<List<Atom>, Optional<Atom>> fn) {
        return mem.isFn(name, fn);
    }

    /**
     * Returns the current logical time of the system.
     */
    public long getLogicalTime() {
        return logicalTime.get();
    }

    /**
     * Increments and returns the logical time. Typically called by the agent or simulation loop.
     */
    public long tick() {
        return logicalTime.incrementAndGet();
    }

    /**
     * Adds an Atom to the Memory, returning the canonical instance.
     */
    public <A extends Atom> A add(A atom) {
        return mem.add(atom);
    }

    /**
     * Convenience method to add an equality rule `(= premise conclusion)`.
     */
    public Expr EQ(Atom premise, Atom conclusion) {
        return add(E(SYMBOL_EQ, premise, conclusion));
    }

    /**
     * Convenience method to add a type assertion `(: instance type)`.
     */
    public Expr TYPE(Atom instance, Atom type) {
        return add(E(SYMBOL_COLON, instance, type));
    }

    /**
     * Evaluates an expression using the MeTTa interpreter with default depth.
     */
    public List<Atom> eval(Atom expr) {
        return interp.eval(expr, INTERPRETER_DEFAULT_MAX_DEPTH);
    }

    /**
     * Evaluates an expression using the MeTTa interpreter with specified max depth.
     */
    public List<Atom> eval(Atom expr, int maxDepth) {
        return interp.eval(expr, maxDepth);
    }

    /**
     * Convenience method to get the single 'best' result (highest confidence * strength).
     */
    public Optional<Atom> evalBest(Atom expr) {
        return eval(expr).stream().max(Comparator.comparingDouble(a -> mem.valueOrDefault(a).getWeightedTruth()));
    }

    /**
     * Performs a pattern matching query against the Memory.
     */
    public List<Answer> query(Atom pattern) {
        return mem.query(pattern);
    }

    /**
     * Runs the agent loop within a specified environment and goal.
     */
    public void runAgent(Game env, Atom goal, int maxCycles) {
        agent.run(env, goal, maxCycles);
    }

    /**
     * Shuts down the maintenance scheduler gracefully.
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
     * Performs periodic maintenance (decay and forgetting).
     */
    private void performMaintenance() {
        mem.decayAndForget();
    }

    // --- Core Data Structures ---

    /**
     * Enumeration of the different types of Atoms.
     */
    public enum AtomType {SYM, VAR, EXPR, IS}

    /**
     * Base sealed interface for all elements in the Memory. Atoms are structurally immutable.
     * Equality and hashcode are based on the structural identifier {@link #id()}.
     */
    public sealed interface Atom {
        /**
         * A unique string identifier representing the structure of the Atom.
         */
        String id();

        /**
         * Returns the type of this Atom.
         */
        AtomType type();

        // Convenience casting methods
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

    /**
     * Interface for the environment the agent interacts with.
     */
    public interface Game {
        /**
         * Returns a list of Atoms representing the agent's current perception.
         */
        List<Atom> perceive(Cog cog);

        /**
         * Returns a list of Atoms representing available actions in the current state.
         */
        List<Atom> actions(Cog cog, Atom currentState);

        /**
         * Executes an action, returning the new percepts and reward.
         */
        Act exe(Cog cog, Atom action);

        /**
         * Returns true if the environment/game simulation should continue running.
         */
        boolean isRunning();
    }

    /**
     * Represents named constants, interned for efficiency via {@link Mem}.
     */
    public record Sym(String name) implements Atom {
        // Keep a static cache for interning symbols across different Cog instances if needed,
        // but primary management is within Memory per Cog instance.
        private static final ConcurrentMap<String, Sym> SYMBOL_CACHE = new ConcurrentHashMap<>();

        public static Sym of(String name) {
            // Note: Memory.add ensures canonical instance *within* the space.
            // This cache is more for potential static sharing or symbol creation outside a space.
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
            return this == o || (o instanceof Sym(String name1) && name.equals(name1));
        }
    }

    /**
     * Represents a variable, distinguished by the '$' prefix in its ID.
     */
    public record Var(String name) implements Atom {
        public Var {
            if (name.startsWith(VAR_PREFIX))
                throw new IllegalArgumentException("Var name should not include prefix '" + VAR_PREFIX + "'");
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

    /**
     * Represents a composite expression (MeTTa lists/trees). Structural equality uses cached ID.
     */
    public record Expr(String id, List<Atom> children) implements Atom {
        // Cache IDs for performance - relies on children being canonical Atoms from Memory
        private static final ConcurrentMap<List<Atom>, String> idCache = new ConcurrentHashMap<>();

        /**
         * Creates an Expression, ensuring children list is immutable and calculating/caching the structural ID.
         */
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

        /**
         * Returns the first element (head) of the expression, or null if empty.
         */
        public @Nullable Atom head() {
            return children.isEmpty() ? null : children.getFirst();
        }

        /**
         * Returns a list containing all elements except the first (tail). Returns empty list if empty.
         */
        public List<Atom> tail() {
            return children.isEmpty() ? emptyList() : children.subList(1, children.size());
        }

        @Override
        public String toString() {
            return id();
        } // Use ID for concise representation

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            // Efficient check using cached ID, assumes IDs are unique structural hashes.
            return this == o || (o instanceof Expr ea && id.equals(ea.id));
        }
    }

    // --- Metadata Records ---

    /**
     * Wraps external Java values, or a function to be executed. Equality uses the provided ID.
     */
    public record Is<T>(String id, @Nullable T value,
                        @Nullable Function<List<Atom>, Optional<Atom>> fn) implements Atom {

        /**
         * Creates a grounded Atom for a non-Fn value, deriving an ID automatically.
         */
        public Is(T value) {
            this(deriveId(value), value, null);
        }

        /**
         * Creates a grounded Atom for a non-Fn value with a specific ID.
         */
        public Is(String id, T value) {
            this(id, value, null);
        }

        /**
         * Creates a grounded Atom for Fn logic, using its name as the ID.
         */
        public Is(String name, Function<List<Atom>, Optional<Atom>> fn) {
            this(name, null, fn);
        }

        // Helper to create a default ID based on type and value hash
        private static <T> String deriveId(T value) {
            if (value == null) return "is:null:null";
            // Simple types often have good toString(), use hash for complex/long ones
            var valStr = value.toString();
            var typeName = value.getClass().getSimpleName();
            var valuePart = (value instanceof String || value instanceof Number || value instanceof Boolean || valStr.length() < 30)
                    ? valStr : String.valueOf(valStr.hashCode());
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
            if (isFn()) return "IsFn:" + id;
            var valueStr = String.valueOf(value);
            // Provide slightly more informative default toString for common types
            if (value instanceof String) return "\"" + valueStr + "\"";
            if (value instanceof Number || value instanceof Boolean) return valueStr;
            // Fallback to detailed representation
            return "is:" + (value != null ? value.getClass().getSimpleName() : "null") + ":"
                    + (valueStr.length() > 30 ? valueStr.substring(0, 27) + "..." : valueStr); // Shorten long value strings
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            // ID uniquely identifies the concept/value/function.
            return this == o || (o instanceof Is<?> ga && id.equals(ga.id));
        }
    }

    /**
     * Immutable record holding Truth, Pri, and Time metadata.
     */
    public record Value(Truth truth, Pri pri, Time access) {
        public static final Value DEFAULT = new Value(Truth.UNKNOWN, Pri.DEFAULT, Time.DEFAULT);

        // Factory methods for creating updated Value instances
        public Value withTruth(Truth newTruth) {
            return new Value(newTruth, pri, access);
        }

        public Value withPri(Pri newPri) {
            return new Value(truth, newPri, access);
        }

        public Value withTime(Time newTime) {
            return new Value(truth, pri, newTime);
        }

        public Value updateTime(long now) {
            return new Value(truth, pri, new Time(now));
        }

        /**
         * Boosts pri and updates access time. Returns a new Value instance.
         */
        public Value boost(double boostAmount, long now) {
            return this.withPri(pri.boost(boostAmount)).updateTime(now);
        }

        /**
         * Decays pri. Returns a new Value instance. Does not update access time.
         */
        public Value decay(long now) { // 'now' available for future complex decay models
            return this.withPri(pri.decay());
        }

        /**
         * Calculates combined current pri modulated by confidence and recency.
         */
        public double getCurrentPri(long now) {
            double timeSinceAccess = Math.max(0, now - access.time());
            // Recency factor: decays exponentially over roughly 3 maintenance cycles
            var recencyFactor = Math.exp(-timeSinceAccess / (FORGETTING_CHECK_INTERVAL_MS * 3.0));
            return pri.getCurrent(recencyFactor) * truth.confidence();
        }

        /**
         * weighted truth (confidence * strength).
         */
        public double getWeightedTruth() {
            return truth.confidence() * truth.strength;
        }

        @Override
        public String toString() {
            return truth + " " + pri + " " + access;
        }
    }

    /**
     * probabilistic truth (Strength, Count/Evidence).
     */
    public record Truth(double strength, double count) {
        public static final Truth TRUE = new Truth(1, 10.0); // Reasonably confident True
        public static final Truth FALSE = new Truth(0, 10.0); // Reasonably confident False
        public static final Truth UNKNOWN = new Truth(0.5, 0.1); // Default, low confidence

        /**
         * strength is [0,1] and count >= 0.
         */
        public Truth {
            strength = unitize(strength);
            count = Math.max(0, count);
        }

        /**
         * confidence based on count and sensitivity factor.
         */
        public double confidence() {
            return count / (count + TRUTH_DEFAULT_SENSITIVITY);
        }

        /**
         * Merges with another TruthValue using Bayesian-like weighted average based on counts.
         */
        public Truth merge(Truth other) {
            if (other == null || other.count == 0) return this;
            if (this.count == 0) return other;
            var totalCount = this.count + other.count;
            var mergedStrength = (this.strength * this.count + other.strength * other.count) / totalCount;
            return new Truth(mergedStrength, totalCount);
        }

        @Override
        public String toString() {
            return String.format("%c%.3f,%.2f%c", '%', strength, count, '%');
        }
    }

    /**
     * Immutable record for Short-Term (STI) and Long-Term (LTI) Pri.
     */
    public record Pri(double sti, double lti) {
        public static final Pri DEFAULT = new Pri(PRI_INITIAL_STI, PRI_INITIAL_STI * PRI_INITIAL_LTI_FACTOR);

        /**
         * Canonical constructor ensures values are [0,1].
         */
        public Pri {
            sti = unitize(sti);
            lti = unitize(lti);
        }


        /**
         * Returns a new PriValue after applying decay. LTI learns slowly from decayed STI.
         */
        public Pri decay() {
            var decayedSti = sti * (1 - PRI_STI_DECAY_RATE);
            // LTI decays slower and gains a fraction of the decayed STI value
            var ltiGain = sti * PRI_STI_DECAY_RATE * PRI_STI_TO_LTI_RATE; // LTI learns from what STI *lost*
            var decayedLti = lti * (1 - PRI_LTI_DECAY_RATE) + ltiGain;
            return new Pri(decayedSti, decayedLti);
        }

        /**
         * Returns a new PriValue after applying boost. Boost affects STI directly, LTI indirectly.
         */
        public Pri boost(double boostAmount) {
            if (boostAmount <= 0) return this;
            var boostedSti = unitize(sti + boostAmount); // Additive boost to STI, capped at 1
            // LTI boost is proportional to the STI change and the boost amount itself
            var ltiBoostFactor = PRI_STI_TO_LTI_RATE * Math.abs(boostAmount);
            var boostedLti = unitize(lti + (boostedSti - sti) * ltiBoostFactor); // LTI learns based on STI *increase*
            return new Pri(boostedSti, boostedLti);
        }

        /**
         * Calculates combined pri, weighted by a recency factor (applied externally).
         */
        public double getCurrent(double recencyFactor) {
            // Weighted average, giving more weight to STI modulated by recency
            return sti * recencyFactor * 0.6 + lti * 0.4;
        }

        @Override
        public String toString() {
            return String.format("$%.3f,%.3f", sti, lti);
        }
    }

    /**
     * Immutable record representing a discrete time point (logical time).
     */
    public record Time(long time) {
        public static final Time DEFAULT = new Time(0L);

        @Override
        public String toString() {
            return "@" + time;
        }
    }


    // --- Unification Engine ---

    /**
     * Central knowledge repository managing Atoms and their metadata (Values).
     */
    public static class Mem {
        private final ConcurrentMap<String, Atom> atomsById = new ConcurrentHashMap<>(1024); // ID -> Atom lookup (canonical store)
        private final ConcurrentMap<Atom, AtomicReference<Value>> storage = new ConcurrentHashMap<>(1024); // Atom -> Value Ref
        // Index: Head Atom -> Set<ExpressionAtom ID> (for faster rule/query lookup)
        private final ConcurrentMap<Atom, ConcurrentSkipListSet<String>> indexByHead = new ConcurrentHashMap<>();
        // Potential future indices: indexByContainedAtom, indexByType

        private final Supplier<Long> timeSource;

        public Mem(Supplier<Long> timeSource) {
            this.timeSource = timeSource;
        }

        /**
         * Adds an atom or retrieves the canonical instance if it already exists.
         * Initializes or updates metadata (access time, small boost).
         */
        @SuppressWarnings("unchecked") // Cast to A is safe due to computeIfAbsent logic
        public <A extends Atom> A add(A atom) {
            // Ensure canonical Atom instance is used/stored by checking ID first
            var canonicalAtom = (A) atomsById.computeIfAbsent(atom.id(), id -> atom);

            long now = timeSource.get();
            // Ensure metadata exists and update access time/boost slightly
            var valueRef = storage.computeIfAbsent(canonicalAtom, k -> {
                var initialValue = Value.DEFAULT.withPri(new Pri(PRI_INITIAL_STI, PRI_INITIAL_STI * PRI_INITIAL_LTI_FACTOR)).updateTime(now);
                updateIndices(k); // Index the new atom when metadata is first created
                return new AtomicReference<>(initialValue);
            });
            // Apply small access boost even if atom already existed
            valueRef.updateAndGet(v -> v.boost(PRI_BOOST_ON_ACCESS * 0.1, now));

            checkMemoryAndTriggerForgetting(); // Trigger forgetting if memory grows too large
            return canonicalAtom;
        }

        /**
         * Retrieves an Atom instance by its unique ID string, boosting its pri.
         */
        public Optional<Atom> getAtom(String id) {
            return Optional.ofNullable(atomsById.get(id)).map(this::boostAndGet);
        }

        /**
         * Retrieves the canonical Atom instance if present, boosting its pri.
         */
        public Optional<Atom> getAtom(Atom atom) {
            // Use atomsById to ensure we boost the canonical instance
            return Optional.ofNullable(atomsById.get(atom.id())).map(this::boostAndGet);
        }

        private Atom boostAndGet(Atom atom) {
            updateValue(atom, v -> v.boost(PRI_BOOST_ON_ACCESS, timeSource.get()));
            return atom;
        }

        /**
         * Retrieves the current Value (Truth, Pri, Time) for an Atom.
         */
        public Optional<Value> value(Atom atom) {
            // Use canonical atom from atomsById map to look up value
            return Optional.ofNullable(atomsById.get(atom.id()))
                    .flatMap(canonicalAtom -> Optional.ofNullable(storage.get(canonicalAtom)))
                    .map(AtomicReference::get);
        }

        /**
         * Retrieves the current Value or returns Value.DEFAULT if the atom is not in the space.
         */
        public Value valueOrDefault(Atom atom) {
            return value(atom).orElse(Value.DEFAULT);
        }

        /**
         * Atomically updates the Value associated with an Atom using an updater function. Also applies revision boost if truth changes significantly.
         */
        public void updateValue(Atom atom, Function<Value, Value> updater) {
            var canonicalAtom = atomsById.get(atom.id());
            if (canonicalAtom == null) return; // Atom not in space

            var valueRef = storage.get(canonicalAtom);
            if (valueRef != null) { // Check if metadata exists (should always exist if canonicalAtom exists)
                long now = timeSource.get();
                valueRef.updateAndGet(current -> {
                    var updated = updater.apply(current).updateTime(now); // Apply update and set time
                    // Calculate revision boost based on confidence change
                    var confidenceDiff = updated.truth.confidence() - current.truth.confidence();
                    var boost = (confidenceDiff > TRUTH_REVISION_CONFIDENCE_THRESHOLD)
                            ? PRI_BOOST_ON_REVISION_MAX * updated.truth.confidence() // Boost proportional to new confidence
                            : 0.0;
                    return boost > 0 ? updated.boost(boost, now) : updated; // Apply boost if significant change
                });
            }
        }

        /**
         * Updates the truth value, merging with existing truth using Bayesian logic.
         */
        public void updateTruth(Atom atom, Truth newTruth) {
            updateValue(atom, v -> v.withTruth(v.truth.merge(newTruth)));
        }

        /**
         * Boosts the pri of an atom.
         */
        public void boost(Atom atom, double amount) {
            updateValue(atom, v -> v.boost(amount, timeSource.get()));
        }

        // --- Atom Factory Methods (Add to this Space) ---
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
         * Performs pattern matching using the Unification engine. Uses indices to find candidates efficiently.
         */
        public List<Answer> query(Atom pattern) {
            // Ensure pattern exists in the space & boost it slightly
            var queryPattern = add(pattern); // Use canonical instance
            boost(queryPattern, PRI_BOOST_ON_ACCESS * 0.2);

            // Find candidate atoms using indices
            Stream<Atom> candidateStream;
            if (queryPattern instanceof Expr pExpr && pExpr.head() != null) {
                // Primary strategy: Use index for expressions starting with the same head.
                var head = pExpr.head();
                candidateStream = indexByHead.getOrDefault(head, new ConcurrentSkipListSet<>()).stream()
                        .map(this::getAtom) // Get Atom from ID
                        .flatMap(Optional::stream);
                // Also consider a direct match for the pattern itself if it's complex
                candidateStream = Stream.concat(candidateStream, Stream.of(queryPattern).filter(storage::containsKey));
            } else if (queryPattern instanceof Var) {
                // Var matches everything initially, stream all stored atoms. Costly!
                // Consider adding constraints or limiting var-only queries if performance is critical.
                candidateStream = storage.keySet().stream();
            } else { // Sym or Is pattern
                // Direct match is the most likely case.
                candidateStream = Stream.of(queryPattern).filter(storage::containsKey);
                // Could also check indices if Symbols/Is atoms are indexed by containment in the future.
            }

            // Filter candidates by minimum confidence and perform unification
            List<Answer> results = new ArrayList<>();
            var unification = new Unify(this); // Create Unify instance (lightweight)
            var checkCount = 0;
            // Limit the number of candidates to check to prevent runaway queries
            var maxChecks = Math.min(5000 + size() / 10, 15000); // Dynamic limit based on space size

            var candidateIterator = candidateStream.iterator();
            while (candidateIterator.hasNext() && results.size() < INTERPRETER_MAX_RESULTS && checkCount < maxChecks) {
                var candidate = candidateIterator.next();
                checkCount++;

                // Filter by confidence *before* expensive unification
                var candidateValue = valueOrDefault(candidate);
                if (candidateValue.truth.confidence() < TRUTH_MIN_CONFIDENCE_MATCH) {
                    continue;
                }

                // Attempt unification
                unification.unify(queryPattern, candidate, Bind.EMPTY)
                        .ifPresent(bind -> {
                            boost(candidate, PRI_BOOST_ON_ACCESS); // Boost successful matches
                            results.add(new Answer(candidate, bind));
                        });
            }
            // Sort results by weighted truth value (confidence * strength) - higher is better
            results.sort(Comparator.comparingDouble((Answer ans) -> valueOrDefault(ans.resultAtom()).getWeightedTruth()).reversed());

            return results.stream().limit(INTERPRETER_MAX_RESULTS).toList(); // Apply final limit
        }


        /**
         * Decays pri of all atoms and removes ("forgets") those below the pri threshold.
         */
        synchronized void decayAndForget() {
            final long now = timeSource.get();
            var initialSize = storage.size();
            if (initialSize == 0) return;

            List<Atom> candidatesForRemoval = new ArrayList<>();
            var decayCount = 0;

            // 1. Decay and identify removal candidates in one pass
            for (var entry : storage.entrySet()) {
                var atom = entry.getKey();
                var valueRef = entry.getValue();

                // Skip protected symbols and var from potential removal list
                var isProtected = (atom instanceof Sym(
                        String name
                ) && PROTECTED_SYMBOLS.contains(name)) || atom instanceof Var;

                // Atomically decay the value
                var decayedValue = valueRef.updateAndGet(v -> v.decay(now));
                decayCount++;

                // Check if eligible for removal based on pri (if not protected)
                if (!isProtected && decayedValue.getCurrentPri(now) < PRI_MIN_FORGET_THRESHOLD) {
                    candidatesForRemoval.add(atom);
                }
            }

            var removedCount = 0;
            // 2. Forget if memory pressure exists or a significant number of low-pri atoms found
            var targetSize = FORGETTING_MAX_MEM_SIZE_TRIGGER * FORGETTING_TARGET_MEM_SIZE_FACTOR / 100;
            var memoryPressure = initialSize > FORGETTING_MAX_MEM_SIZE_TRIGGER;
            var significantLowPri = candidatesForRemoval.size() > initialSize * 0.05; // e.g., > 5% are candidates

            if (!candidatesForRemoval.isEmpty() && (memoryPressure || significantLowPri || initialSize > targetSize)) {
                // Sort candidates by current pri (lowest first)
                candidatesForRemoval.sort(Comparator.comparingDouble(atom -> valueOrDefault(atom).getCurrentPri(now)));

                // Determine how many to remove
                var removalTargetCount = memoryPressure
                        ? Math.max(0, initialSize - targetSize) // Remove excess under pressure
                        : candidatesForRemoval.size();          // Remove all candidates otherwise (up to sorted list size)

                var actuallyRemoved = 0;
                for (var atomToRemove : candidatesForRemoval) {
                    if (actuallyRemoved >= removalTargetCount) break;
                    // Re-check threshold, as pri might change slightly due to time effects
                    if (valueOrDefault(atomToRemove).getCurrentPri(now) < PRI_MIN_FORGET_THRESHOLD) {
                        if (removeAtomInternal(atomToRemove)) {
                            actuallyRemoved++;
                        }
                    }
                }
                removedCount = actuallyRemoved;
            }

            if (removedCount > 0 || decayCount > 0 && initialSize > 10) { // Avoid logging spam for tiny spaces
                System.out.printf("Memory Maintenance: Decayed %d atoms. Removed %d low-pri atoms. Size %d -> %d.%n",
                        decayCount, removedCount, initialSize, storage.size());
            }
        }

        /**
         * Internal helper to remove an atom and update indices. Returns true if removed.
         */
        boolean removeAtomInternal(Atom atom) {
            if (storage.remove(atom) != null) {
                atomsById.remove(atom.id());
                removeIndices(atom);
                return true;
            }
            return false;
        }

        /**
         * Checks memory size and triggers asynchronous forgetting if threshold exceeded.
         */
        private void checkMemoryAndTriggerForgetting() {
            if (storage.size() > FORGETTING_MAX_MEM_SIZE_TRIGGER) {
                CompletableFuture.runAsync(this::decayAndForget);
            }
        }

        // --- Index Management ---
        private void updateIndices(Atom atom) {
            if (atom instanceof Expr e && e.head() != null) {
                indexByHead.computeIfAbsent(e.head(), h -> new ConcurrentSkipListSet<>()).add(atom.id());
            }
            // Add other indexing logic here (e.g., by type, by contained atom)
        }

        private void removeIndices(Atom atom) {
            if (atom instanceof Expr e && e.head() != null) {
                indexByHead.computeIfPresent(e.head(), (k, v) -> {
                    v.remove(atom.id());
                    return v.isEmpty() ? null : v; // Remove set entirely if empty
                });
            }
            // Add removal logic for other indices here
        }

        /**
         * Returns the number of atoms currently in the Memory.
         */
        public int size() {
            return storage.size();
        }
    }

    /**
     * Represents var binds resulting from unification. Immutable.
     */
    public record Bind(Map<Var, Atom> map) {
        public static final Bind EMPTY = new Bind(emptyMap());

        /**
         * Canonical constructor ensures the internal map is immutable.
         */
        public Bind {
            map = Map.copyOf(map);
        }

        public boolean isEmpty() {
            return map.isEmpty();
        }

        public Optional<Atom> get(Var var) {
            return Optional.ofNullable(map.get(var));
        }

        /**
         * Recursively resolves binds for a var, handling chained binds and detecting cycles.
         */
        public Optional<Atom> getRecursive(Var var) {
            var current = map.get(var);
            if (current == null) return empty();

            Set<Var> visited = new HashSet<>();
            visited.add(var);

            while (current instanceof Var v) {
                if (!visited.add(v)) return empty(); // Cycle detected!
                var next = map.get(v);
                if (next == null) return Optional.of(v); // Return the last unbound var in the chain
                current = next;
            }
            return Optional.of(current); // Return the final concrete value (non-var)
        }

        @Override
        public String toString() {
            return map.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", ", "{", "}"));
        }
    }

    /**
     * Result of a successful pattern match query, containing the matched atom and binds.
     */
    public record Answer(Atom resultAtom, Bind bind) {
    }


    // --- MeTTa Interpreter ---

    /**
     * Performs unification between Atoms using an iterative, stack-based approach.
     */
    public static class Unify {
        private final Mem space; // Reference to space (currently unused, but potentially for type checks)

        public Unify(Mem space) {
            this.space = space;
        }

        /**
         * Attempts to unify a pattern Atom with an instance Atom, starting with initial binds.
         * Returns an Optional containing the resulting Binds if successful, or empty otherwise.
         */
        public Optional<Bind> unify(Atom pattern, Atom instance, Bind initialBind) {
            Deque<Pair<Atom, Atom>> stack = new ArrayDeque<>();
            stack.push(new Pair<>(pattern, instance));
            var currentBinds = initialBind;

            while (!stack.isEmpty()) {
                var task = stack.pop();
                // Apply current binds *before* comparison/processing
                var p = subst(task.a, currentBinds);
                var i = subst(task.b, currentBinds);

                // 1. Identical after substitution? Success for this pair.
                if (p.equals(i)) continue;

                // 2. Pattern is Var? Try to bind it.
                if (p instanceof Var pVar) {
                    if (containsVar(i, pVar)) return empty(); // Occurs check fail
                    currentBinds = mergeBind(currentBinds, pVar, i);
                    if (currentBinds == null) return empty(); // Merge conflict
                    continue;
                }

                // 3. Instance is Var? Try to bind it. (Symmetrical to case 2)
                if (i instanceof Var iVar) {
                    if (containsVar(p, iVar)) return empty(); // Occurs check fail
                    currentBinds = mergeBind(currentBinds, iVar, p);
                    if (currentBinds == null) return empty(); // Merge conflict
                    continue;
                }

                // 4. Both are Expressions? Unify children.
                if (p instanceof Expr pExpr && i instanceof Expr iExpr) {
                    var pChildren = pExpr.children();
                    var iChildren = iExpr.children();
                    var pn = pChildren.size();
                    if (pn != iChildren.size()) return empty(); // Different arity
                    // Push child pairs onto stack in reverse order for LIFO processing
                    for (var j = pn - 1; j >= 0; j--) {
                        stack.push(new Pair<>(pChildren.get(j), iChildren.get(j)));
                    }
                    continue;
                }

                // 5. Mismatch in type or structure (and not covered above)? Failure.
                return empty();
            }
            // Stack is empty and no failures encountered? Success.
            return Optional.of(currentBinds);
        }

        /**
         * Applies binds to an Atom, recursively replacing vars with their bound values.
         */
        public Atom subst(Atom atom, Bind bind) {
            if (bind.isEmpty() || !(atom instanceof Var || atom instanceof Expr)) {
                return atom; // No binds or not substitutable type
            }

            return switch (atom) {
                case Var var ->
                    // Resolve recursively, then subst the result. If no bind, return var itself.
                        bind.getRecursive(var)
                                .map(val -> subst(val, bind)) // Subst recursively *after* resolving
                                .orElse(var);
                case Expr expr -> {
                    var changed = false;
                    List<Atom> newChildren = new ArrayList<>(expr.children().size());
                    for (var child : expr.children()) {
                        var substChild = subst(child, bind);
                        if (child != substChild) changed = true;
                        newChildren.add(substChild);
                    }
                    // Return new Expr only if children actually changed, preserving object identity otherwise
                    yield changed ? new Expr(newChildren) : expr;
                }
                default -> atom; // Symbols, Is Atoms are not subst into
            };
        }

        // Occurs check: Checks if a var `var` occurs within `expr` after substitution.
        private boolean containsVar(Atom expr, Var var) {
            return switch (expr) {
                case Var v -> v.equals(var); // Direct match
                case Expr e -> e.children().stream().anyMatch(c -> containsVar(c, var)); // Check children recursively
                default -> false; // Symbols, Is atoms cannot contain vars
            };
        }

        // Merges a new bind (var -> value) into existing binds.
        // Returns updated binds or null if there's a conflict.
        private @Nullable Bind mergeBind(Bind current, Var var, Atom value) {
            var existingBindOpt = current.getRecursive(var);

            if (existingBindOpt.isPresent()) {
                // Var already bound, unify the existing value with the new value.
                // If they unify, the resulting binds (which might be augmented) are returned.
                // If they don't unify, it's a conflict, return null.
                return unify(existingBindOpt.get(), value, current).orElse(null);
            } else {
                // Var not bound yet, add the new bind.
                var m = new HashMap<>(current.map());
                m.put(var, value);
                return new Bind(m);
            }
        }
    }


    // --- Agent Framework ---

    /**
     * Represents the result of an agent's action in the environment.
     */
    public record Act(List<Atom> newPercepts, double reward) {
    }

    /**
     * A simple game environment for agent demonstration.
     */
    static class SimpleGame implements Game {
        private final Atom posA, posB, posGoal, moveAtoB, moveBtoGoal, moveOther, statePred, selfSym;
        private final Cog cog;
        private Atom currentStateSymbol; // Internal state of the game

        SimpleGame(Cog cog) {
            this.cog = cog;
            // Define game symbols within the Cog's space
            posA = cog.S("Pos_A");
            posB = cog.S("Pos_B");
            posGoal = cog.S("AtGoal");
            moveAtoB = cog.S("Move_A_B");
            moveBtoGoal = cog.S("Move_B_Goal");
            moveOther = cog.S("Move_Other");
            statePred = cog.S("State"); // Standard predicate for state facts
            selfSym = cog.S("Self");    // Standard symbol for the agent itself
            currentStateSymbol = posA;  // Initial state
        }

        @Override
        public List<Atom> perceive(Cog cog) {
            // The agent perceives its current state as a fact
            return List.of(cog.E(statePred, selfSym, currentStateSymbol));
        }

        @Override
        public List<Atom> actions(Cog cog, Atom currentStateAtom) {
            // Available actions depend on the internal state symbol
            if (currentStateSymbol.equals(posA)) return List.of(moveAtoB, moveOther);
            if (currentStateSymbol.equals(posB)) return List.of(moveBtoGoal, moveOther);
            return List.of(moveOther); // Only 'other' action if at goal or error state
        }

        @Override
        public Act exe(Cog cog, Atom actionSymbol) {
            var reward = -0.1; // Small cost for taking any action
            var stateChanged = false;

            if (currentStateSymbol.equals(posA) && actionSymbol.equals(moveAtoB)) {
                currentStateSymbol = posB;
                reward = 0.1; // Small reward for correct move
                stateChanged = true;
                System.out.println("Env: Moved A -> B");
            } else if (currentStateSymbol.equals(posB) && actionSymbol.equals(moveBtoGoal)) {
                currentStateSymbol = posGoal;
                reward = 1.0; // Large reward for reaching the goal
                stateChanged = true;
                System.out.println("Env: Moved B -> Goal!");
            } else if (actionSymbol.equals(moveOther)) {
                // 'Move_Other' does nothing but incurs cost
                reward = -0.2;
                System.out.println("Env: Executed 'Move_Other'");
            } else {
                // Invalid action for the current state
                reward = -0.5; // Penalty for invalid action
                System.out.println("Env: Invalid action attempted: " + actionSymbol.id() + " from state " + currentStateSymbol.id());
            }

            // Return new perception based on potentially updated state, and the calculated reward
            return new Act(perceive(cog), reward);
        }

        @Override
        public boolean isRunning() {
            // The game stops when the goal state is reached
            return !currentStateSymbol.equals(posGoal);
        }
    }

    /**
     * Parses MeTTa syntax strings into Cog Atoms.
     */
    private static class MettaParser {
        private final Cog cog; // Needed to create atoms within the Cog's space context

        MettaParser(Cog cog) {
            this.cog = cog;
        }

        private Atom parseSymbolOrIs(String text) {
            // Handle special symbols recognised as Is values or core symbols
            return switch (text) {
                case "True" -> SYMBOL_TRUE; // Use canonical True symbol
                case "False" -> SYMBOL_FALSE; // Use canonical False symbol
                case "Nil" -> SYMBOL_NIL; // Use canonical Nil symbol
                default -> cog.S(text); // Default to a regular Symbol
            };
        }

        private String unescapeString(String s) {
            return s.replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\");
        }

        private List<Token> tokenize(String text) {
            List<Token> tokens = new ArrayList<>();
            var line = 1;
            var col = 1;
            var i = 0;
            while (i < text.length()) {
                var c = text.charAt(i);
                var startCol = col;

                if (Character.isWhitespace(c)) {
                    if (c == '\n') {
                        line++;
                        col = 1;
                    } else {
                        col++;
                    }
                    i++;
                    continue;
                }

                if (c == '(') {
                    tokens.add(new Token(TokenType.LPAREN, "(", line, startCol));
                    i++;
                    col++;
                    continue;
                }
                if (c == ')') {
                    tokens.add(new Token(TokenType.RPAREN, ")", line, startCol));
                    i++;
                    col++;
                    continue;
                }

                // Comment: ';' to end of line
                if (c == ';') {
                    var start = i;
                    while (i < text.length() && text.charAt(i) != '\n') i++;
                    // Optionally add comment token or just skip
                    // tokens.add(new Token(TokenType.COMMENT, text.substring(start, i), line, startCol));
                    // We skip comments by default as they aren't part of the structure
                    if (i < text.length() && text.charAt(i) == '\n') {
                        line++;
                        col = 1;
                        i++;
                    } else {
                        col = 1;
                    } // Reset col after EOL or EOF
                    continue;
                }

                // String literal: "..."
                if (c == '"') {
                    var start = i;
                    i++;
                    col++; // Skip opening quote
                    var sb = new StringBuilder();
                    var escaped = false;
                    while (i < text.length()) {
                        var nc = text.charAt(i);
                        if (nc == '"' && !escaped) {
                            i++;
                            col++; // Skip closing quote
                            tokens.add(new Token(TokenType.STRING, text.substring(start, i), line, startCol));
                            break;
                        }
                        if (nc == '\n') throw new MettaParseException("Unterminated string literal at line " + line);
                        sb.append(nc);
                        escaped = (nc == '\\' && !escaped);
                        i++;
                        col++;
                    }
                    if (i == text.length() && (tokens.isEmpty() || tokens.getLast().type != TokenType.STRING)) {
                        throw new MettaParseException("Unterminated string literal at end of input");
                    }
                    continue;
                }

                // Var: $...
                if (c == VAR_PREFIX.charAt(0)) {
                    var start = i;
                    i++;
                    col++;
                    while (i < text.length() && !Character.isWhitespace(text.charAt(i)) && text.charAt(i) != '(' && text.charAt(i) != ')') {
                        i++;
                        col++;
                    }
                    var varName = text.substring(start, i);
                    if (varName.length() == 1)
                        throw new MettaParseException("Invalid var name '$' at line " + line);
                    tokens.add(new Token(TokenType.VAR, varName, line, startCol));
                    continue;
                }

                // Symbol or Number
                var start = i;
                var maybeNumber = Character.isDigit(c) || (c == '-' && i + 1 < text.length() && Character.isDigit(text.charAt(i + 1)));
                var hasDot = false;
                var hasExp = false;

                while (i < text.length() && !Character.isWhitespace(text.charAt(i)) && text.charAt(i) != '(' && text.charAt(i) != ')' && text.charAt(i) != ';') {
                    var nc = text.charAt(i);
                    if (nc == '.') {
                        if (hasDot || hasExp) maybeNumber = false; // Multiple dots or dot after E
                        else hasDot = true;
                    } else if (nc == 'e' || nc == 'E') {
                        if (hasExp) maybeNumber = false; // Multiple E's
                        else hasExp = true;
                    } else if (nc == '+' || nc == '-') {
                        // Sign only valid at start or after E
                        if (i != start && !(hasExp && (text.charAt(i - 1) == 'e' || text.charAt(i - 1) == 'E'))) {
                            maybeNumber = false;
                        }
                    } else if (!Character.isDigit(nc)) {
                        maybeNumber = false;
                    }
                    i++;
                    col++;
                }
                var value = text.substring(start, i);

                try {
                    if (maybeNumber) {
                        Double.parseDouble(value); // Check if valid number format
                        tokens.add(new Token(TokenType.NUMBER, value, line, startCol));
                    } else {
                        tokens.add(new Token(TokenType.SYMBOL, value, line, startCol));
                    }
                } catch (NumberFormatException e) {
                    // If parsing as number failed, treat as symbol
                    tokens.add(new Token(TokenType.SYMBOL, value, line, startCol));
                }
            }
            // tokens.add(new Token(TokenType.EOF, "", line, col)); // Don't add EOF, just return list
            return tokens;
        }

        // Wrap iterator for parsing methods
        private Atom parseAtomFromTokens(PeekableIterator<Token> it) {
            if (!it.hasNext()) throw new MettaParseException("Unexpected end of input");
            var token = it.next(); // Consume the token
            return switch (token.type) {
                case LPAREN -> parseExprFromTokens(it);
                case VAR -> cog.V(token.text.substring(VAR_PREFIX.length()));
                case SYMBOL -> parseSymbolOrIs(token.text);
                case NUMBER -> cog.IS(Double.parseDouble(token.text));
                case STRING -> cog.IS(unescapeString(token.text.substring(1, token.text.length() - 1)));
                case RPAREN -> throw new MettaParseException("Unexpected ')' at line " + token.line);
                case COMMENT -> {
                    if (!it.hasNext()) throw new MettaParseException("Input ended with a comment");
                    yield parseAtomFromTokens(it); // Skip comment and parse next
                }
                case EOF -> throw new MettaParseException("Unexpected EOF");
            };
        }

        private Expr parseExprFromTokens(PeekableIterator<Token> it) {
            List<Atom> children = new ArrayList<>();
            while (true) {
                if (!it.hasNext()) throw new MettaParseException("Unterminated expression, unexpected end of input");
                var next = it.peek();
                if (next.type == TokenType.RPAREN) {
                    it.next(); // Consume ')'
                    return cog.E(children);
                }
                if (next.type == TokenType.COMMENT) {
                    it.next(); // Consume comment
                    continue; // Skip comment
                }
                children.add(parseAtomFromTokens(it)); // Parse child atom
            }
        }

        public Atom parse(String text) {
            var tokens = tokenize(text);
            if (tokens.isEmpty()) throw new MettaParseException("Cannot parse empty input");
            var it = new PeekableIterator<>(tokens.iterator());
            var result = parseAtomFromTokens(it);
            while (it.hasNext()) { // Check for trailing non-comment tokens
                if (it.peek().type != TokenType.COMMENT) {
                    var trailing = it.next();
                    throw new MettaParseException("Extra token found after main expression: '" + trailing.text + "' at line " + trailing.line);
                }
                it.next(); // Consume comment
            }
            return result;
        }

        public List<Atom> parseAll(String text) {
            var tokens = tokenize(text);
            List<Atom> results = new ArrayList<>();
            var it = new PeekableIterator<>(tokens.iterator());
            while (it.hasNext()) {
                // Skip leading comments
                while (it.hasNext() && it.peek().type == TokenType.COMMENT) {
                    it.next();
                }
                if (it.hasNext()) { // Check again after skipping comments
                    results.add(parseAtomFromTokens(it));
                }
            }
            return results;
        }

        private enum TokenType {LPAREN, RPAREN, SYMBOL, VAR, NUMBER, STRING, COMMENT, EOF}

        private record Token(TokenType type, String text, int line, int col) {
        }

        // Simple peekable iterator helper
        private static class PeekableIterator<T> implements Iterator<T> {
            private final Iterator<T> iterator;
            private T nextElement;

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
                if (nextElement == null) throw new NoSuchElementException();
                var current = nextElement;
                advance();
                return current;
            }

            public T peek() {
                if (nextElement == null) throw new NoSuchElementException();
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
    } // Simple pair for internal use (e.g., Unify stack)

    /**
     * Initializes core symbols, types, and Is functions using the MettaParser.
     */
    private static class Core {
        private final Cog cog;

        Core(Cog cog) {
            this.cog = cog;
        }

        /**
         * Applies a binary double operation, evaluating arguments if necessary.
         */
        private static Optional<Atom> applyNumOp(List<Atom> args, Cog cog, BinaryOperator<Double> op) {
            if (args.size() != 2) return empty(); // Expect exactly two args
            var n1 = numValue(args.get(0), cog);
            var n2 = numValue(args.get(1), cog);
            // If both args resolve to numbers, apply the operation
            return n1.isPresent() && n2.isPresent()
                    ? Optional.of(cog.IS(op.apply(n1.get(), n2.get())))
                    : empty();
        }

        /**
         * Applies a binary double comparison, evaluating arguments if necessary.
         */
        private static Optional<Atom> applyNumFn(List<Atom> args, Cog cog, BiPredicate<Double, Double> op) {
            if (args.size() != 2) return empty();
            var n1 = numValue(args.get(0), cog);
            var n2 = numValue(args.get(1), cog);
            // If both resolve, return True or False symbol
            return n1.isPresent() && n2.isPresent()
                    ? Optional.of(op.test(n1.get(), n2.get()) ? SYMBOL_TRUE : SYMBOL_FALSE)
                    : empty();
        }

        // Extracts Double value from an Atom, evaluating it first if needed.
        private static Optional<Double> numValue(@Nullable Atom atom, Cog cog) {
            if (atom == null) return empty();
            // If atom is already Is<Number>, use its value directly.
            if (atom instanceof Is<?> g && g.value() instanceof Number n) {
                return Optional.of(n.doubleValue());
            }
            // Otherwise, evaluate the atom and check if the *best* result is Is<Number>.
            return cog.evalBest(atom)
                    .filter(res -> res instanceof Is<?> g && g.value() instanceof Number)
                    .map(res -> ((Number) ((Is<?>) res).value()).doubleValue());
        }

        // Extracts String value from an Atom, evaluating it first if needed.
        private static Optional<String> stringValue(@Nullable Atom atom) {
            if (atom == null) return empty();

            String y = null;
            // If atom is already Is<String>, use its value directly.
            if (atom instanceof Is<?> g && g.value() instanceof String s) {
                y = s;
            }
            // Symbols evaluate to their name
            else if (atom instanceof Sym(String name)) {
                y = name;
            }
            // Numbers convert to string
            else if (atom instanceof Is<?> g && g.value() instanceof Number n) {
                y = n.toString();
            }
            // Booleans convert to "True"/"False"
            else if (SYMBOL_TRUE.equals(atom)) y = "True";
            else if (SYMBOL_FALSE.equals(atom)) y = "False";

            // TODO: Add evaluation step like getNumericValue if needed?
            // For Concat, maybe direct conversion is sufficient.
            // If evaluation is needed:
            // return cog.evalBest(atom)... filter Is<String> ...

            return y == null ? empty() : Optional.of(y);
        }

        void initialize() {
            // Assign core symbols (parser will retrieve/create canonical instances)
            Cog.SYMBOL_EQ = cog.S("=");
            Cog.SYMBOL_COLON = cog.S(":");
            Cog.SYMBOL_ARROW = cog.S("->");
            Cog.SYMBOL_TYPE = cog.S("Type");
            Cog.SYMBOL_TRUE = cog.S("True");
            Cog.SYMBOL_FALSE = cog.S("False");
            Cog.SYMBOL_SELF = cog.S("Self");
            Cog.SYMBOL_NIL = cog.S("Nil");

            // Define core types and Is functions programmatically
            // (Parser handles data, but functions need Java lambda definitions)
            initIsFn();

            // Load core MeTTa rules and type definitions from string
            cog.load("""
                        ; Basic Types
                        (: = Type)
                        (: : Type)
                        (: -> Type)
                        (: Type Type)
                        (: True Type)
                        (: False Type)
                        (: Nil Type)
                        (: Number Type)
                        (: String Type)
                        (: Bool Type)
                    
                        ; Booleans belong to Bool type
                        (: True Bool)
                        (: False Bool)
                    
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
                    
                        ; If control structure rules
                        (= (If True $then $else) $then)
                        (= (If False $then $else) $else)
                    
                        ; Optional: Basic list processing (example)
                        ; (= (head (Cons $h $t)) $h)
                        ; (= (tail (Cons $h $t)) $t)
                    """);

            // Ensure core symbols have high confidence/pri from the start
            Stream.of(SYMBOL_EQ, SYMBOL_COLON, SYMBOL_ARROW, SYMBOL_TYPE, SYMBOL_TRUE, SYMBOL_FALSE, SYMBOL_NIL, SYMBOL_SELF)
                    .forEach(sym -> {
                        cog.mem.updateTruth(sym, Truth.TRUE); // Mark as definitely existing
                        cog.mem.boost(sym, 1); // Max pri boost initially
                    });
        }

        private void initIsFn() {
            // --- Core Operations ---
            cog.IS("match", args -> { // (match <space> <pattern> <template>)
                if (args.size() != 3) return empty();
                // Use target space if provided as Is<Memory>, else default space
                var targetSpace = (args.get(0) instanceof Is<?> g && g.value() instanceof Mem ts) ? ts : cog.mem;
                var pattern = args.get(1);
                var template = args.get(2);
                var queryResults = targetSpace.query(pattern);
                // Apply template substitution for each result bind
                return Optional.of(cog.IS(queryResults.stream()
                        .map(answer -> cog.interp.subst(template, answer.bind()))
                        .toList())); // Return results as Is<List<Atom>>
            });

            cog.IS("eval", args -> args.isEmpty() ? empty() : cog.evalBest(args.getFirst())); // (eval <expr>) -> best result

            cog.IS("add-atom", args -> args.isEmpty() ? empty() : Optional.of(cog.add(args.getFirst()))); // (add-atom <atom>) -> adds atom

            cog.IS("remove-atom", args -> { // (remove-atom <atom>) -> True/False
                var removed = !args.isEmpty() && cog.mem.removeAtomInternal(args.getFirst());
                return Optional.of(removed ? SYMBOL_TRUE : SYMBOL_FALSE);
            });

            cog.IS("get-value", args -> args.isEmpty() ? empty() : Optional.of(cog.IS(cog.mem.value(args.getFirst())))); // (get-value <atom>) -> Is<Optional<Value>>

            cog.IS("&self", args -> Optional.of(cog.IS(cog.mem))); // (&self) -> Is<Memory> reference to own space

            // --- Arithmetic / Comparison --- (Internal implementation detail)
            cog.IS("_+", args -> applyNumOp(args, cog, Double::sum));
            cog.IS("_-", args -> applyNumOp(args, cog, (a, b) -> a - b));
            cog.IS("_*", args -> applyNumOp(args, cog, (a, b) -> a * b));
            cog.IS("_/", args -> applyNumOp(args, cog, (a, b) -> Math.abs(b) < 1e-12 ? Double.NaN : a / b)); // Avoid division by zero
            cog.IS("_==", args -> applyNumFn(args, cog, (a, b) -> Math.abs(a - b) < 1e-9)); // Tolerance for float equality
            cog.IS("_>", args -> applyNumFn(args, cog, (a, b) -> a > b));
            cog.IS("_<", args -> applyNumFn(args, cog, (a, b) -> a < b));

            // --- String Ops --- (Example)
            cog.IS("Concat", args -> { // (Concat $a $b ...) -> concatenated string
                return Optional.of(cog.IS(args.stream()
                        .map(Core::stringValue) // Evaluate/get string value of each arg
                        .flatMap(Optional::stream)
                        .collect(Collectors.joining())));
            });
        }
    }

    /**
     * Evaluates MeTTa expressions using unification against equality rules stored in the Memory.
     */
    public class Interp {
        private final Cog cog; // Access to Cog components like space, unify

        public Interp(Cog cog) {
            this.cog = cog;
        }

        /**
         * Applies binds to an Atom. Delegates to Unify.
         */
        public Atom subst(Atom atom, Bind bind) {
            return cog.unify.subst(atom, bind);
        }

        /**
         * Evaluates an Atom with a specified maximum recursion depth.
         */
        public List<Atom> eval(Atom atom, int maxDepth) {
            // Detect cycles within a single evaluation path
            var results = evalRecursive(atom, maxDepth, new HashSet<String>());

            // If evaluation yielded no results (e.g., only cycles or depth limit), return original atom.
            if (results.isEmpty())
                return List.of(atom);
            else
                // Filter distinct results and limit the total number.
                // Sorting by value can happen here or in the caller (e.g., evalBest)
                return results.stream()
                        .distinct()
                        .limit(INTERPRETER_MAX_RESULTS)
                        .toList();
        }

        /**
         * Recursive evaluation helper with depth tracking and cycle detection.
         */
        private List<Atom> evalRecursive(Atom atom, int depth, Set<String> visitedInPath) {
            // 1. Base Cases: Depth limit, cycle detected, or non-evaluatable atom type
            if (depth <= 0) return List.of(atom); // Depth limit reached
            if (!visitedInPath.add(atom.id())) return List.of(atom); // Cycle detected in this path

            List<Atom> combinedResults = new ArrayList<>();
            try {
                switch (atom) {
                    // Vars and Symbols evaluate to themselves (unless part of matched rule later)
                    case Sym s -> combinedResults.add(s);
                    case Var v -> combinedResults.add(v);
                    // Non-Fn Is evaluate to themselves
                    case Is<?> ga when !ga.isFn() -> combinedResults.add(ga);

                    // Expressions are the main target for evaluation
                    case Expr expr -> {
                        // Strategy 1: Try specific equality rules `(= <expr> $result)`
                        var resultVar = V("evalRes" + depth); // Unique var name per depth
                        Atom specificQuery = E(SYMBOL_EQ, expr, resultVar);
                        var specificMatches = cog.mem.query(specificQuery);

                        if (!specificMatches.isEmpty()) {
                            for (var match : specificMatches) {
                                var target = match.bind.get(resultVar);
                                if (target.isPresent()) {
                                    // Recursively evaluate the result of the rule
                                    combinedResults.addAll(evalRecursive(target.get(), depth - 1, new HashSet<>(visitedInPath)));
                                    if (combinedResults.size() >= INTERPRETER_MAX_RESULTS) break;
                                }
                            }
                            // If specific rules found results, often we don't proceed further, but MeTTa allows non-determinism.
                            // Let's continue to general rules unless we hit the result limit.
                        }

                        // Strategy 2: Try general equality rules `(= <pattern> <template>)` if no specific match or still need results
                        // Avoid re-running if already at result limit
                        if (combinedResults.size() < INTERPRETER_MAX_RESULTS) {
                            var pVar = V("p" + depth);
                            var tVar = V("t" + depth);
                            var generalQuery = E(SYMBOL_EQ, pVar, tVar);
                            var ruleMatches = cog.mem.query(generalQuery);

                            for (var ruleMatch : ruleMatches) {
                                var pattern = ruleMatch.bind.get(pVar).orElse(null);
                                var template = ruleMatch.bind.get(tVar).orElse(null);

                                // Skip if rule is invalid or is the specific rule already tried
                                if (pattern == null || template == null || pattern.equals(expr)) continue;

                                // Try to unify the expression with the rule's pattern
                                var exprBindOpt = cog.unify.unify(pattern, expr, Bind.EMPTY);
                                if (exprBindOpt.isPresent()) {
                                    // If unification succeeds, subst binds into the template and evaluate it
                                    var result = subst(template, exprBindOpt.get());
                                    combinedResults.addAll(evalRecursive(result, depth - 1, new HashSet<>(visitedInPath)));
                                    if (combinedResults.size() >= INTERPRETER_MAX_RESULTS) break;
                                }
                            }
                        }

                        // Strategy 3: Try executing head if it's a Is Function (Applicative Order Evaluation)
                        // Only if no rules applied or still need results
                        if (combinedResults.size() < INTERPRETER_MAX_RESULTS && expr.head() instanceof Is<?> ga && ga.isFn()) {
                            List<Atom> evaluatedArgs = new ArrayList<>();
                            var argEvalOk = true;
                            // Evaluate arguments first
                            for (var arg : expr.tail()) {
                                var argResults = evalRecursive(arg, depth - 1, new HashSet<>(visitedInPath));
                                // Require exactly one result for functional application (simplification)
                                if (argResults.size() != 1) {
                                    // Could also choose the 'best' result via evalBest if multiple results allowed.
                                    argEvalOk = false;
                                    break;
                                }
                                evaluatedArgs.add(argResults.getFirst());
                            }

                            if (argEvalOk) {
                                // Execute the Is function with evaluated arguments
                                var execResult = ga.apply(evaluatedArgs);
                                if (execResult.isPresent()) {
                                    // Evaluate the result of the Is function execution
                                    combinedResults.addAll(evalRecursive(execResult.get(), depth - 1, new HashSet<>(visitedInPath)));
                                }
                            }
                        }

                        // Strategy 4: No rule applied or execution possible? Evaluate children and reconstruct (if changed)
                        // This handles cases like `(Cons (add 1 1) Nil)` -> `(Cons 2 Nil)`
                        // Only do this if strategies 1-3 yielded no results.
                        if (combinedResults.isEmpty()) {
                            var childrenChanged = false;
                            List<Atom> evaluatedChildren = new ArrayList<>();
                            if (expr.head() != null) {
                                var headResults = evalRecursive(expr.head(), depth - 1, new HashSet<>(visitedInPath));
                                if (headResults.size() == 1) { // Require single head result
                                    var newHead = headResults.getFirst();
                                    evaluatedChildren.add(newHead);
                                    if (!newHead.equals(expr.head())) childrenChanged = true;
                                } else {
                                    evaluatedChildren.add(expr.head()); // Keep original if eval failed/ambiguous
                                }
                            }
                            for (var child : expr.tail()) {
                                var childResults = evalRecursive(child, depth - 1, new HashSet<>(visitedInPath));
                                if (childResults.size() == 1) { // Require single child result
                                    var newChild = childResults.getFirst();
                                    evaluatedChildren.add(newChild);
                                    if (!newChild.equals(child)) childrenChanged = true;
                                } else {
                                    evaluatedChildren.add(child); // Keep original
                                }
                            }
                            // If any child changed, return the new expression, otherwise the original
                            combinedResults.add(childrenChanged ? cog.E(evaluatedChildren) : expr);
                        }

                        // If after all strategies, no results were added, the expression evaluates to itself.
                        if (combinedResults.isEmpty()) {
                            combinedResults.add(expr);
                        }
                    }
                    // Fn Is atoms (when evaluated directly, not as expr head) - currently evaluate to self
                    // Could potentially execute with empty args: ga.execute(emptyList())? TBD.
                    case Is<?> ga -> combinedResults.add(ga);
                }
            } finally {
                visitedInPath.remove(atom.id()); // Backtrack: remove from visited set for this path
            }
            return combinedResults;
        }
    }

    // Helper for unitizing values (clamping between 0 and 1) - moved from Pri constructor
    // Useful if needed elsewhere, but currently only used there. Keep static if broader utility emerges.
    // private static double unitize(double v) { return Math.max(0.0, Math.min(1.0, v)); }


    // --- Bootstrap Logic ---

    /**
     * Agent implementing perceive-evaluate-act-learn cycle using MeTTa evaluation and learning.
     */
    public class Agent {
        private final Cog cog;
        private final RandomGenerator random = RandomGenerator.getDefault();
        // Standard symbols used by the agent logic
        private final Atom STATE_PRED, GOAL_PRED, ACTION_PRED, UTILITY_PRED, IMPLIES_PRED, SEQ_PRED, SELF_SYM;
        private @Nullable Atom currentAgentStateAtom = null; // Agent's representation of the current state

        public Agent(Cog cog) {
            this.cog = cog;
            // Define or retrieve standard agent symbols
            this.STATE_PRED = cog.S("State");
            this.GOAL_PRED = cog.S("Goal");
            this.ACTION_PRED = cog.S("Action"); // Maybe unused directly, actions are usually symbols
            this.UTILITY_PRED = cog.S("Utility"); // Predicate for utility facts/rules
            this.IMPLIES_PRED = cog.S("Implies"); // Predicate for learned transition rules
            this.SEQ_PRED = cog.S("Seq");       // Represents sequence of state/action
            this.SELF_SYM = cog.S("Self");      // Agent's identifier
        }

        /**
         * Runs the agent loop within a given environment, pursuing a goal pattern.
         */
        public void run(Game env, Atom goalPattern, int maxCycles) {
            System.out.println("\n--- Agent Run Start ---");
            initializeGoal(goalPattern);
            // Initial perception processing
            currentAgentStateAtom = processPerception(env.perceive(cog));

            for (var cycle = 0; cycle < maxCycles && env.isRunning(); cycle++) {
                var time = cog.tick(); // Advance logical time
                System.out.printf("\n--- Agent Cycle %d (Time: %d) ---%n", cycle + 1, time);
                System.out.println("Agent State: " + currentAgentStateAtom);

                // Check if goal is achieved based on agent's current knowledge
                if (isGoalAchieved(goalPattern)) {
                    System.out.println("*** Agent: Goal Achieved! ***");
                    break;
                }

                // Decide on an action
                var actionOpt = selectAction(env.actions(cog, currentAgentStateAtom));

                if (actionOpt.isPresent()) {
                    var action = actionOpt.get();
                    System.out.println("Agent: Selected Action: " + action);

                    // Execute action in environment
                    var result = env.exe(cog, action);

                    // Process new percepts and update state representation
                    var nextState = processPerception(result.newPercepts);

                    // Learn from the experience (state transition and utility)
                    learn(currentAgentStateAtom, action, nextState, result.reward);

                    // Update agent's current state
                    currentAgentStateAtom = nextState;
                } else {
                    System.out.println("Agent: No action available or selected. Idling.");
                    // Optionally, agent could perform internal reasoning (evaluation) even when idling
                }
            }
            if (!env.isRunning() && !isGoalAchieved(goalPattern)) {
                System.out.println("--- Agent Run Finished (Environment Stopped) ---");
            } else if (!isGoalAchieved(goalPattern)) {
                System.out.println("--- Agent Run Finished (Max Cycles Reached) ---");
            } else {
                System.out.println("--- Agent Run Finished ---");
            }
        }

        /**
         * Adds the goal to the Memory and boosts its pri.
         */
        private void initializeGoal(Atom goalPattern) {
            // Represent the goal as a fact `(Goal <goal_pattern>)`
            Atom goalAtom = cog.add(cog.E(GOAL_PRED, goalPattern));
            cog.mem.boost(goalAtom, PRI_BOOST_ON_GOAL_FOCUS);
            // Also boost the pattern itself slightly
            cog.mem.boost(goalPattern, PRI_BOOST_ON_GOAL_FOCUS * 0.8);
            System.out.println("Agent: Goal initialized -> " + goalAtom);
        }

        /**
         * Checks if the goal pattern currently holds true in the Memory.
         */
        private boolean isGoalAchieved(Atom goalPattern) {
            // Query the space to see if the goal pattern currently matches any facts
            var achieved = !cog.mem.query(goalPattern).isEmpty();
            if (achieved) {
                System.out.println("Agent: Goal check query successful for: " + goalPattern);
            }
            return achieved;
        }

        /**
         * Processes percepts, adds them to the space, and returns a representative state Atom.
         */
        private Atom processPerception(List<Atom> percepts) {
            if (percepts.isEmpty()) {
                // Handle empty perception? Return previous state or a default 'Unknown' state?
                return currentAgentStateAtom != null ? currentAgentStateAtom : cog.S("State_Unknown");
            }
            // Add each percept as a fact with high truth and boost pri
            List<String> perceptIDs = Collections.synchronizedList(new ArrayList<>()); // For stable hashing
            percepts.forEach(p -> {
                var factAtom = cog.add(p); // Add the percept atom itself
                cog.mem.updateTruth(factAtom, new Truth(1.0, AGENT_DEFAULT_PERCEPTION_COUNT)); // High confidence perception
                cog.mem.boost(factAtom, PRI_BOOST_ON_PERCEPTION);
                perceptIDs.add(factAtom.id()); // Collect IDs for state hashing
            });

            // Create a single Atom representing the combined state based on percept IDs
            // Simple approach: Sort IDs and hash the joined string
            Collections.sort(perceptIDs);
            var stateHash = Integer.toHexString(String.join("|", perceptIDs).hashCode());
            var stateAtom = cog.S("State_" + stateHash); // Create a symbol representing this unique state configuration

            // Ensure this state symbol exists and is marked as current/true
            cog.add(stateAtom);
            cog.mem.updateTruth(stateAtom, Truth.TRUE);
            cog.mem.boost(stateAtom, PRI_BOOST_ON_PERCEPTION); // Boost the state symbol itself

            return stateAtom;
        }

        /**
         * Selects an action based on learned utility or explores randomly.
         */
        private Optional<Atom> selectAction(List<Atom> availableActions) {
            if (availableActions.isEmpty()) return empty();

            // 1. Evaluate utility of available actions
            Map<Atom, Double> utilities = new ConcurrentHashMap<>();
            var valVar = cog.V("val"); // Var to capture utility value in query

            // Use parallel stream for potential speedup if many actions or complex utility eval
            availableActions.parallelStream().forEach(action -> {
                var actionAtom = cog.add(action); // Ensure canonical action atom
                // Query for the utility rule: (= (Utility <actionAtom>) $val)
                Atom utilQuery = cog.E(SYMBOL_EQ, cog.E(UTILITY_PRED, actionAtom), valVar);
                // Find the best (highest value) utility associated with this action
                var utility = cog.mem.query(utilQuery).stream()
                        .map(answer -> answer.bind().get(valVar)) // Get the bind for $val
                        .flatMap(Optional::stream)
                        .filter(atom -> atom instanceof Is<?> g && g.value() instanceof Number) // Ensure it's Is number
                        .mapToDouble(atom -> ((Number) ((Is<?>) atom).value()).doubleValue())
                        .max() // Find the maximum defined utility (if multiple rules exist)
                        .orElse(0.0); // Default utility is 0 if no rule found

                // Store utility if above a minimum threshold (to avoid selecting clearly bad actions)
                if (utility > AGENT_UTILITY_THRESHOLD) {
                    utilities.put(actionAtom, utility);
                }
            });

            // 2. Select best action based on utility
            var bestAction = utilities.entrySet().stream()
                    .max(Map.Entry.comparingByValue());

            if (bestAction.isPresent()) {
                System.out.printf("Agent: Selecting by utility: %s (Util: %.3f)%n", bestAction.get().getKey(), bestAction.get().getValue());
                return Optional.of(bestAction.get().getKey());
            }

            // 3. Exploration: If no high-utility action found, explore randomly
            if (random.nextDouble() < AGENT_RANDOM_ACTION_PROBABILITY || utilities.isEmpty()) {
                System.out.println("Agent: Selecting random action (exploration or no known good options).");
                // Add the randomly chosen action to the space if it wasn't already evaluated
                return Optional.of(cog.add(availableActions.get(random.nextInt(availableActions.size()))));
            }

            // 4. Fallback: If exploration roll failed, but there were *some* utilities below threshold,
            // pick the least bad one known. Otherwise, random (covered by step 3).
            // This case is less likely if AGENT_UTILITY_THRESHOLD > 0.
            var leastBadAction = utilities.entrySet().stream()
                    .max(Map.Entry.comparingByValue()); // Max of the below-threshold utilities
            if (leastBadAction.isPresent()) {
                System.out.printf("Agent: Selecting least bad known action: %s (Util: %.3f)%n", leastBadAction.get().getKey(), leastBadAction.get().getValue());
                return Optional.of(leastBadAction.get().getKey());
            }

            // Should be covered by random selection, but as a final fallback:
            System.out.println("Agent: Fallback random action selection.");
            return Optional.of(cog.add(availableActions.get(random.nextInt(availableActions.size()))));
        }


        /**
         * Learns state transition rules and updates action utility based on experience.
         */
        private void learn(Atom prevState, Atom action, Atom nextState, double reward) {
            // Basic Q-learning / Temporal Difference learning update for utility
            if (prevState == null || action == null || nextState == null) return; // Need valid transition

            // 1. Learn/Update Transition Rule: (Implies (Seq <prevState> <action>) <nextState>)
            // This rule captures "If I was in prevState and did action, I ended up in nextState"
            Atom sequence = cog.add(cog.E(SEQ_PRED, prevState, action));
            Atom implication = cog.add(cog.E(IMPLIES_PRED, sequence, nextState));
            // Update truth based on observation count (simple reinforcement)
            cog.mem.updateTruth(implication, new Truth(1.0, AGENT_LEARNED_RULE_COUNT)); // Reinforce this transition
            cog.mem.boost(implication, PRI_BOOST_ON_ACCESS * 1.2); // Boost learned rules slightly more

            // 2. Learn/Update Utility Rule: (= (Utility <action>) <value>)
            // Q(s,a) = Q(s,a) + alpha * (reward + gamma * max_a'(Q(s',a')) - Q(s,a))
            // Simplified version (no lookahead / gamma=0, or using direct reward):
            // U(a) = U(a) + alpha * (reward - U(a))

            var valVar = cog.V("val");
            Atom utilityPattern = cog.E(UTILITY_PRED, action); // e.g., (Utility Move_A_B)
            Atom utilityRulePattern = cog.E(SYMBOL_EQ, utilityPattern, valVar); // e.g., (= (Utility Move_A_B) $val)

            // Find the current utility value atom associated with this action
            var currentUtilityValueAtomOpt = cog.mem.query(utilityRulePattern).stream()
                    .map(answer -> answer.bind().get(valVar))
                    .flatMap(Optional::stream)
                    .filter(atom -> atom instanceof Is<?> g && g.value() instanceof Number)
                    .findFirst(); // Assume one primary utility rule per action for simplicity

            double currentUtility = currentUtilityValueAtomOpt
                    .map(atom -> ((Number) ((Is<?>) atom).value()).doubleValue())
                    .orElse(0.0); // Default utility is 0

            var learningRate = 0.2; // Alpha
            // Simple update: move utility towards the immediate reward received
            var newUtility = currentUtility + learningRate * (reward - currentUtility);

            // Create the new utility value as a Is Atom
            Atom newUtilityValueAtom = cog.IS(newUtility);

            // Create or update the utility rule in the space
            // (= (Utility <action>) <newUtilityValueAtom>)
            Atom newUtilityRule = cog.EQ(utilityPattern, newUtilityValueAtom); // EQ adds the rule to the space

            // Set high truth for the newly learned/updated rule
            cog.mem.updateTruth(newUtilityRule, Truth.TRUE); // Mark this as the current best utility estimate
            cog.mem.boost(newUtilityRule, PRI_BOOST_ON_ACCESS * 1.5); // Boost utility rules strongly

            // Optional: Decay or remove the *old* utility rule atom if it existed
            // currentUtilityValueAtomOpt.ifPresent(oldValAtom -> {
            //     cog.space.query(cog.E(SYMBOL_EQ, utilityPattern, oldValAtom)) // Find the specific old rule
            //         .forEach(oldRuleAns -> cog.space.removeAtomInternal(oldRuleAns.resultAtom()));
            // });
            // For simplicity, let's allow multiple rules and rely on query sorting/selection or forgetting.

            System.out.printf("  Learn: Action %s Utility %.3f -> %.3f (Reward: %.2f)%n", action.id(), currentUtility, newUtility, reward);
        }
    }
}
