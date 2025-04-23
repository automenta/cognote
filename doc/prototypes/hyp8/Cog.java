package dumb.hyp8; // Package declaration (adjust as needed)

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Cog.java: A Java implementation of the Cog runtime, supporting Atomspace,
 * pattern matching, MeTTa-like evaluation, and agent execution.
 * Converted from JavaScript, aiming for correctness, compactness, and modern Java features.
 */
public class Cog {

    // --- Core Data Structures ---

    private final Memory memory; // Atomspace instance
    private final JVM jvm; // JVM interaction instance
    private final ScheduledExecutorService executor; // For background tasks (agents, decay)
    private final Map<String, Agent> agents; // Map of active agents by ID
    private final Map<Object, ScheduledFuture<?>> scheduledTasks; // Tracks scheduled tasks by key

    /**
     * Initializes the Cog runtime, memory, JVM bridge, and executor service.
     */
    public Cog() {
        this.memory = new Memory();
        this.jvm = new JVM(this.memory);
        // Create a daemon thread pool for background tasks
        this.executor = Executors.newScheduledThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() / 2),
                r -> {
                    Thread t = Executors.defaultThreadFactory().newThread(r);
                    t.setDaemon(true);
                    t.setName("CogExecutor");
                    return t;
                });
        this.agents = new ConcurrentHashMap<>(); // Use concurrent map for agents
        this.scheduledTasks = new ConcurrentHashMap<>(); // Use concurrent map for tasks
        bootstrap(); // Load initial rules
        // Schedule periodic memory maintenance tasks
        scheduledTasks.put("decayAndForget", executor.scheduleAtFixedRate(memory::decayAndForget, 10, 10, TimeUnit.SECONDS));
        scheduledTasks.put("optimize", executor.scheduleAtFixedRate(memory::optimize, 60, 60, TimeUnit.SECONDS));
        System.out.println("Cog Runtime Initialized. Atom count: " + memory.atoms.size());
    }

    // --- Memory Management (Atomspace) ---

    /**
     * Runs a suite of tests to verify Cog functionality.
     */
    public static boolean runTests() {
        System.out.println("\n--- Running Cog Tests ---");
        Cog cog = new Cog(); // Create a fresh Cog instance for tests.
        final Atom TRUE = cog.memory.symTrue; // Use interned 'true' for expected results.
        final Atom FALSE = cog.memory.symFalse; // Use interned 'false'.
        int passed = 0;
        int failed = 0;

        // Define tests: { name, code, expectedAtom }
        // Use interned atoms for expected values where possible for reliable comparison.
        List<Map<String, Object>> tests = List.of(
                Map.of("name", "Symbol Equality", "code", "(assertEquals S S)", "expected", TRUE),
                Map.of("name", "Number Equality", "code", "(assertEquals (Number 123) (Number 123))", "expected", TRUE),
                Map.of("name", "Simple Arithmetic +", "code", "(assertEquals (+ (Number 2) (Number 3)) (Number 5))", "expected", cog.parse("(Number 5)")),
                Map.of("name", "Simple Arithmetic -", "code", "(assertEquals (- (Number 5) (Number 2)) (Number 3))", "expected", cog.parse("(Number 3)")),
                Map.of("name", "Simple Arithmetic *", "code", "(assertEquals (* (Number 2) (Number 3)) (Number 6))", "expected", cog.parse("(Number 6)")),
                // BigDecimal division result includes decimals by default with scale 10.
                Map.of("name", "Simple Arithmetic /", "code", "(assertEquals (/ (Number 6) (Number 2)) (Number 3.0000000000))", "expected", cog.parse("(Number 3.0000000000)")),
                Map.of("name", "Comparison >", "code", "(assertEquals (> (Number 5) (Number 3)) true)", "expected", TRUE),
                Map.of("name", "Comparison <", "code", "(assertEquals (< (Number 3) (Number 5)) true)", "expected", TRUE),
                Map.of("name", "Logic AND True", "code", "(assertEquals (and true true) true)", "expected", TRUE),
                Map.of("name", "Logic AND False", "code", "(assertEquals (and true false) false)", "expected", FALSE),
                Map.of("name", "Logic OR True", "code", "(assertEquals (or false true) true)", "expected", TRUE),
                Map.of("name", "Logic OR False", "code", "(assertEquals (or false false) false)", "expected", FALSE),
                Map.of("name", "Logic NOT", "code", "(assertEquals (not false) true)", "expected", TRUE),
                Map.of("name", "Basic If True", "code", "(assertEquals (if true A B) A)", "expected", cog.memory.intern(new Symbol("A"))),
                Map.of("name", "Basic If False", "code", "(assertEquals (if false A B) B)", "expected", cog.memory.intern(new Symbol("B"))),
                Map.of("name", "If with Eval Condition", "code", "(assertEquals (if (< (Number 2) (Number 5)) Good Bad) Good)", "expected", cog.memory.intern(new Symbol("Good"))),
                Map.of("name", "Type Check Symbol True", "code", "(assertEquals (is-symbol A) true)", "expected", TRUE),
                Map.of("name", "Type Check Symbol False", "code", "(assertEquals (is-symbol (A B)) false)", "expected", FALSE),
                Map.of("name", "Type Check Expression", "code", "(assertEquals (is-expression (A B)) true)", "expected", TRUE),
                Map.of("name", "Type Check Variable", "code", "(assertEquals (is-variable $X) true)", "expected", TRUE),
                Map.of("name", "List Head", "code", "(assertEquals (head (A B C)) A)", "expected", cog.memory.intern(new Symbol("A"))),
                Map.of("name", "List Tail", "code", "(assertEquals (tail (A B C)) (B C))", "expected", cog.memory.intern(new Expression(cog.memory.intern(new Symbol("B")), cog.memory.intern(new Symbol("C"))))),
                Map.of("name", "List Cons", "code", "(assertEquals (cons A (B C)) (A B C))", "expected", cog.memory.intern(new Expression(cog.memory.intern(new Symbol("A")), cog.memory.intern(new Symbol("B")), cog.memory.intern(new Symbol("C"))))),
                Map.of("name", "List Nth", "code", "(assertEquals (nth (A B C) (Number 1)) B)", "expected", cog.memory.intern(new Symbol("B"))),
                Map.of("name", "List Length", "code", "(assertEquals (length (A B C)) (Number 3))", "expected", cog.parse("(Number 3)")),
                Map.of("name", "Factorial (Rule)", "code", "(assertEquals (factorial (Number 3)) (Number 6))", "expected", cog.parse("(Number 6)")),
                Map.of("name", "Factorial Base Case", "code", "(assertEquals (factorial (Number 0)) (Number 1))", "expected", cog.parse("(Number 1)"))
        );

        // Execute each test case.
        for (Map<String, Object> test : tests) {
            String name = (String) test.get("name");
            String code = (String) test.get("code");
            Atom expected = (Atom) test.get("expected");

            System.out.println("\nTest: " + name + "\n  Code: " + code);
            try {
                Atom parsed = cog.parse(code); // Parse the test code.
                Atom result = cog.evaluate(parsed); // Evaluate the parsed atom.
                boolean success = expected.equals(result); // Compare result with expected atom.

                if (success) {
                    System.out.println("  Result: " + result + " - PASS");
                    passed++;
                } else {
                    System.out.println("  Result: " + result + " - FAIL (Expected: " + expected + ")");
                    failed++;
                }
            } catch (Exception e) { // Catch errors during parsing or evaluation.
                System.out.println("  Result: EXCEPTION - FAIL");
                System.err.println("    Exception during test '" + name + "': " + e);
                e.printStackTrace(System.err); // Print stack trace for debugging.
                failed++;
            }
        }

        System.out.println("\n--- Agent Test ---");
        if (failed == 0) { // Run agent test only if core tests passed.
            cog.addAgent("testAgent007"); // Add and start an agent.
            System.out.println("Agent 'testAgent007' started. Observing behavior for 2 seconds...");
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } // Wait briefly.
            System.out.println("Agent test observation complete.");
        } else {
            System.out.println("Skipping agent test due to core test failures.");
        }

        // Print test summary.
        System.out.println("\n--- Test Summary ---");
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);
        System.out.println("Total: " + (passed + failed));

        cog.shutdown(); // Clean up Cog resources.

        return failed == 0; // Return true if all tests passed.
    }

    // --- Parsing Logic ---

    /**
     * Main method to run tests when the class is executed directly.
     */
    public static void main(String[] args) {
        boolean allPassed = runTests();
        System.exit(allPassed ? 0 : 1); // Exit with status 0 on success, 1 on failure.
    }

    // --- Simulated JVM Interaction ---

    /**
     * Parses a string of code into an Atom using the runtime's memory.
     */
    public Atom parse(String code) {
        return Parser.parse(code, this.memory);
    }

    // --- Cog Runtime Orchestration ---

    /**
     * Shuts down the executor service, stops agents, and clears memory.
     */
    public void shutdown() {
        System.out.println("Shutting down Cog Runtime...");
        agents.values().forEach(Agent::stop); // Signal all agents to stop
        executor.shutdown(); // Disable new tasks
        try { // Wait for existing tasks to complete
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                System.err.println("Executor forcing shutdown...");
                executor.shutdownNow(); // Force shutdown if tasks don't finish
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt(); // Preserve interrupt status
        }
        // Clear runtime state
        agents.clear();
        scheduledTasks.clear();
        memory.atoms.clear();
        memory.index = new Memory.Trie(); // Clear index too
        System.out.println("Cog Runtime Shutdown complete.");
    }

    /**
     * Loads foundational MeTTa-like rules into memory during startup.
     */
    private void bootstrap() {
        // Core rules defined using S-expression syntax (rule Pattern Result)
        // FIX: Combined multi-line rules into single lines for the line-by-line parser.
        final String mettaCode = """
                ; Rule Definition Syntax: (rule Pattern Result)
                
                ; Logical operators (expect evaluated args from evaluator)
                (rule (and true true) true)
                (rule (and $a false) false)
                (rule (and false $b) false)
                (rule (and true $b) $b)
                (rule (or true $b) true)
                (rule (or $a true) true)
                (rule (or false $b) $b)
                (rule (not true) false)
                (rule (not false) true)
                
                ; Type checking using Java calls
                (rule (is-symbol $x) (java_call "Atom.isSymbol" $x))
                (rule (is-variable $x) (java_call "Atom.isVariable" $x))
                (rule (is-expression $x) (java_call "Atom.isExpression" $x))
                
                ; List/Expression operations using Java calls
                (rule (get-atoms $e) (java_call "Expression.atoms" $e))
                (rule (length $e) (java_call "Expression.length" $e))
                (rule (is-empty $e) (java_call "Expression.isEmpty" $e))
                (rule (head $e) (java_call "Expression.head" $e))
                (rule (tail $e) (java_call "Expression.tail" $e))
                (rule (nth $e $n) (java_call "Expression.nth" $e $n))
                (rule (cons $h $t) (java_call "Expression.cons" $h $t))
                
                ; Arithmetic using Java calls (expect evaluated numerical args from evaluator)
                (rule (+ $a $b) (java_call "Math.add" $a $b))
                (rule (- $a $b) (java_call "Math.subtract" $a $b))
                (rule (* $a $b) (java_call "Math.multiply" $a $b))
                (rule (/ $a $b) (java_call "Math.divide" $a $b))
                
                ; Comparison using Java calls (expect evaluated numerical args from evaluator)
                (rule (> $a $b) (java_call "Math.greaterThan" $a $b))
                (rule (< $a $b) (java_call "Math.lessThan" $a $b))
                ; Equality check operation (expect evaluated args from evaluator)
                (rule (= $a $b) (java_call "Object.equals" $a $b))
                
                ; Factorial Rule Definition (FIXED: single line)
                (rule (factorial (Number 0)) (Number 1))
                (rule (factorial (Number $n)) (if (> $n (Number 0)) (* (Number $n) (factorial (- (Number $n) (Number 1)))) (Number 1)))
                
                ; Agent Behavior definition (FIXED: single line)
                (rule (agent_behavior $id) (if (> (perceive $id) (Number 0.5)) (act $id) (rest $id)))
                (rule (perceive $id) (java_call "Math.random"))
                (rule (act $id) (java_call "console.log" "Agent" $id "is ACTING"))
                (rule (rest $id) (java_call "console.log" "Agent" $id "is RESTING"))
                
                ; Test Utility Rule (evaluator evaluates args first, then applies '=')
                (rule (assertEquals $a $b) (= $a $b))
                """;

        // Parse the bootstrap code line by line, interning each statement.
        Arrays.stream(mettaCode.split("\\R")) // Split into lines
                .map(String::trim) // Trim whitespace
                .map(line -> line.contains(";") ? line.substring(0, line.indexOf(';')).trim() : line) // Remove comments
                .filter(line -> !line.isEmpty() && line.startsWith("(")) // Keep non-empty S-expressions
                .forEach(statement -> { // Parse each statement
                    try {
                        parse(statement); // Interns the statement (rule) into memory
                    } catch (Exception e) { // Log parsing errors during bootstrap
                        System.err.println("Error parsing bootstrap statement: '" + statement + "' - " + e.getMessage());
                        e.printStackTrace(); // Print stacktrace for parse errors
                    }
                });
    }

    /**
     * Public entry point for evaluating an Atom. Uses a cache for the top-level call.
     */
    public Atom evaluate(Atom atom) {
        // Cache results within a single evaluate() call to handle diamond patterns in evaluation graph.
        Map<Atom, Atom> evalCache = new ConcurrentHashMap<>(); // Use concurrent map for potential future parallel eval
        return evaluateRecursive(atom, 0, evalCache);
    }

    /**
     * Recursive evaluation helper with depth limit and caching.
     */
    private Atom evaluateRecursive(Atom atom, int depth, Map<Atom, Atom> evalCache) {
        final int maxDepth = 50; // Limit recursion depth to prevent stack overflow / infinite loops.
        if (depth > maxDepth) {
            System.err.println("Eval depth limit (" + depth + ") reached for: " + atom);
            return memory.intern(new Expression(Symbol.ERROR_PREFIX, memory.intern(new Symbol("maxDepth")))); // Return error atom
        }

        // Check cache before performing evaluation.
        // Note: Cache key is the *original* atom passed to this level, not intermediate evaluated forms.
        if (evalCache.containsKey(atom)) return evalCache.get(atom);

        // Perform evaluation based on Atom type.
        Atom result = switch (atom) {
            // 1. Base cases: Symbols and Variables are considered values (cannot be reduced further).
            case Symbol s -> s;
            case Variable v -> v; // Unbound variables are values.

            // 2. Handle Expression evaluation.
            case Expression expr -> {
                if (expr.atoms().isEmpty()) yield expr; // Empty expression is a value.

                Atom head = expr.atoms().get(0); // Expression head.
                List<Atom> args = expr.atoms().subList(1, expr.atoms().size()); // Expression arguments.

                // 2a. Special Forms: Handle constructs that control evaluation flow (if, java_call, quote, let, etc.).
                // These are handled *before* evaluating arguments.
                if (head instanceof Symbol headSym) {
                    switch (headSym.name()) {
                        case "if": // (if condition then else)
                            if (args.size() != 3)
                                yield memory.intern(new Expression(Symbol.ERROR_PREFIX, memory.intern(new Symbol("invalidIfFormat"))));
                            Atom condition = evaluateRecursive(args.get(0), depth + 1, evalCache); // Evaluate condition first.
                            boolean isTrue = condition.equals(memory.symTrue); // Check if result is 'true' symbol.
                            // Evaluate either 'then' or 'else' branch based on condition.
                            yield evaluateRecursive(isTrue ? args.get(1) : args.get(2), depth + 1, evalCache);

                        case "java_call": // (java_call "methodName" arg1 arg2 ...)
                            if (args.size() < 1 || !(args.get(0) instanceof Symbol methodNameSym)) {
                                yield memory.intern(new Expression(Symbol.ERROR_PREFIX, memory.intern(new Symbol("invalidJavaCallFormat"))));
                            }
                            String methodName = methodNameSym.name();
                            // Evaluate all arguments *before* calling the JVM function.
                            List<Atom> evaluatedArgs = args.stream().skip(1) // Skip method name symbol.
                                    .map(arg -> evaluateRecursive(arg, depth + 1, evalCache))
                                    .toList();
                            // Execute the JVM call with evaluated arguments.
                            yield jvm.call(methodName, evaluatedArgs);

                            // Add other special forms like 'quote', 'let' here if needed.
                            // case "quote": if (args.size() == 1) yield args.get(0); else yield memory.intern(...error...);
                    }
                }

                // 2b. Standard Evaluation: If not a special form, evaluate head and arguments first.
                Atom evaluatedHead = evaluateRecursive(head, depth + 1, evalCache);
                List<Atom> evaluatedArgs = args.stream()
                        .map(arg -> evaluateRecursive(arg, depth + 1, evalCache))
                        .toList();
                // Reconstruct the expression with evaluated components.
                // Important: Use memory.intern to ensure canonical representation after evaluation.
                Expression evaluatedExpr = memory.intern(new Expression(
                        Stream.concat(Stream.of(evaluatedHead), evaluatedArgs.stream()).toList()
                ));

                // Check if the expression changed after evaluating components.
                // Compare with the original 'expr' passed to this switch case branch,
                // not the 'atom' passed to the function (which might be different due to caching).
                boolean changed = !evaluatedExpr.equals(expr);

                // 2c. Apply Rules: Try to find a matching rule for the *evaluated* expression.
                Expression targetExprForRule = evaluatedExpr;
                // Query for candidate rules (rule P R). Optimization: Could query more specifically if head is symbol.
                Expression ruleQueryPattern = memory.intern(new Expression(memory.symRule, new Variable("p"), new Variable("r")));
                List<Expression> candidateRules = memory.query(ruleQueryPattern, new Binding());

                // Iterate through candidate rules to find the first match.
                for (Expression rule : candidateRules) {
                    // Basic structural check for (rule Pattern Result).
                    if (rule.atoms().size() == 3 && rule.atoms().get(0).equals(memory.symRule)) {
                        Atom pattern = rule.atoms().get(1);
                        Atom resultTemplate = rule.atoms().get(2);

                        // Attempt unification between the rule's pattern and the target expression.
                        Binding unificationResult = Memory.Trie.unify(pattern, targetExprForRule, new Binding()); // Removed unused internFunc

                        if (unificationResult != null) { // If unification succeeds...
                            // Apply the resulting binding to the rule's result template.
                            Atom boundResult = unificationResult.apply(resultTemplate);
                            // Recursively evaluate the instantiated result.
                            yield evaluateRecursive(boundResult, depth + 1, evalCache);
                        }
                    }
                }

                // 2d. No Rule Applied: Decide final result.
                // If evaluating components changed the expression, evaluate the *new* expression.
                // This handles cases where evaluation simplifies args but no direct rule applies to the head.
                if (changed) {
                    yield evaluateRecursive(evaluatedExpr, depth + 1, evalCache);
                } else {
                    // If expression didn't change and no rule applied, it's considered a final value.
                    yield evaluatedExpr;
                }
            }
        }; // End switch

        // Cache the computed result against the original atom key before returning.
        evalCache.put(atom, result);
        return result;
    }

    /**
     * Creates and starts a new agent, or returns the existing one if ID conflicts.
     */
    public Agent addAgent(String id) {
        // Use computeIfAbsent for atomic creation and registration.
        return agents.computeIfAbsent(id, key -> {
            Agent agent = new Agent(key); // Create new agent.
            agent.startAutonomousLoop(); // Start its execution loop.
            System.out.println("Agent '" + key + "' added and started.");
            return agent;
        });
    }

    /**
     * Manually triggers one step for a specific agent asynchronously.
     */
    public void stepAgent(String id) {
        Agent agent = agents.get(id);
        if (agent != null && agent.running) {
            executor.submit(agent::step); // Submit step execution to the thread pool.
        } else if (agent == null) {
            System.err.println("Agent '" + id + "' not found for stepping.");
        }
    }

    /**
     * Base interface for all atomic elements in the Cog system (Symbol, Variable, Expression).
     */
    public sealed interface Atom permits Symbol, Variable, Expression {
        String id(); // Unique identifier string for the atom.
    }

    /**
     * Represents a named constant. Immutable record.
     */
    public record Symbol(String name) implements Atom {
        // Define common symbols as constants for efficiency and clarity
        static final Symbol TRUE = new Symbol("true");
        static final Symbol FALSE = new Symbol("false");
        static final Symbol VOID = new Symbol("void");
        static final Symbol NUMBER = new Symbol("Number");
        static final Symbol RULE = new Symbol("rule");
        static final Symbol ERROR_PREFIX = new Symbol("error:"); // Prefix for error symbols

        @Override
        public String id() {
            return name;
        }

        @Override
        public String toString() {
            return id();
        }
    }

    /**
     * Represents a variable, typically used in patterns. Immutable record.
     */
    public record Variable(String name) implements Atom {
        @Override
        public String id() {
            return "$" + name;
        }

        @Override
        public String toString() {
            return id();
        }
    }

    // --- Core Evaluation Logic ---

    /**
     * Represents a structured expression containing other atoms. Immutable record.
     */
    public record Expression(List<Atom> atoms) implements Atom {
        // Ensure list passed to constructor is made immutable
        public Expression(List<Atom> atoms) {
            this.atoms = List.copyOf(atoms);
        }

        // Convenience constructor for varargs
        public Expression(Atom... atoms) {
            this(List.of(atoms));
        }

        @Override
        public String id() { // Generate unique ID based on sub-atom IDs
            return "(" + atoms.stream().map(Atom::id).collect(Collectors.joining(" ")) + ")";
        }

        @Override
        public String toString() {
            return id();
        } // String representation is the ID

        // Override equals and hashCode for proper collection handling based on content
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Expression that = (Expression) o;
            return atoms.equals(that.atoms);
        }

        @Override
        public int hashCode() {
            return Objects.hash(atoms);
        }
    }

    /**
     * Represents variable bindings during unification/evaluation. Immutable record.
     */
    public record Binding(Map<Variable, Atom> map) {
        public Binding() {
            this(Map.of());
        } // Constructor for empty binding

        // Ensure map passed to constructor is made immutable
        public Binding(Map<Variable, Atom> map) {
            this.map = Map.copyOf(map);
        }

        /**
         * Returns a new Binding extended with variable->atom, or null if conflict occurs.
         */
        public Binding extend(Variable variable, Atom atom) {
            if (map.containsKey(variable)) { // Check for existing binding
                return map.get(variable).equals(atom) ? this : null; // Return self if same, null if conflict
            }
            // Create and return a new Binding with the extension
            Map<Variable, Atom> newMap = new HashMap<>(map);
            newMap.put(variable, atom);
            return new Binding(newMap);
        }

        /**
         * Gets the atom bound to the variable, or the variable itself if unbound.
         */
        public Atom get(Variable variable) {
            return map.getOrDefault(variable, variable);
        }

        /**
         * Applies the binding to substitute variables within an atom recursively.
         */
        public Atom apply(Atom atom) {
            return switch (atom) {
                case Variable v -> get(v); // Substitute variable if bound
                case Expression e -> { // Apply recursively to expression elements
                    List<Atom> appliedAtoms = e.atoms().stream().map(this::apply).toList();
                    // Optimization: return original if no change occurred
                    yield appliedAtoms.equals(e.atoms()) ? e : new Expression(appliedAtoms);
                }
                case Symbol s -> s; // Symbols are constants
            };
        }
    }


    // --- Agent Management ---

    /**
     * Stores an Atom along with its metadata (e.g., priority, timestamp). Uses ConcurrentHashMap for thread-safety.
     */
    static class Entry {
        final Atom atom;
        final Map<String, Object> meta; // Thread-safe metadata map

        Entry(Atom atom, Map<String, Object> initialMeta) {
            this.atom = atom;
            this.meta = new ConcurrentHashMap<>(initialMeta); // Use thread-safe map
            this.meta.putIfAbsent("pri", 1.0); // Default priority
            this.meta.putIfAbsent("time", System.currentTimeMillis()); // Creation time
        }

        /**
         * Updates a metadata field.
         */
        void update(String key, Object value) {
            meta.put(key, value);
        }

        /**
         * Gets priority, handling potential Number types.
         */
        double getPriority() {
            return ((Number) meta.getOrDefault("pri", 1.0)).doubleValue();
        }

        /**
         * Gets creation time.
         */
        long getTime() {
            return ((Number) meta.getOrDefault("time", 0L)).longValue();
        }
    }

    /**
     * Manages the storage, indexing, and retrieval of Atoms (Atomspace).
     */
    static class Memory {
        // Main atom storage: ID -> Entry (Atom + Metadata). Concurrent for thread safety.
        final ConcurrentMap<String, Entry> atoms = new ConcurrentHashMap<>();
        // Statistics tracking.
        final Stats stats = new Stats();
        // Trie index for efficient expression pattern matching. Volatile for safe publication after rebuild.
        volatile Trie index = new Trie();
        // Intern common symbols on initialization for efficiency.
        final Symbol symTrue = intern(Symbol.TRUE);
        final Symbol symFalse = intern(Symbol.FALSE);
        final Symbol symVoid = intern(Symbol.VOID);
        final Symbol symNumber = intern(Symbol.NUMBER);
        final Symbol symRule = intern(Symbol.RULE);

        /**
         * Returns the canonical, interned representation of an atom. Thread-safe.
         */
        public <T extends Atom> T intern(T atom) {
            String id = atom.id();
            Entry existingEntry = atoms.get(id);

            // Fast path: Atom already exists with the correct type.
            if (existingEntry != null && existingEntry.atom.getClass() == atom.getClass()) {
                @SuppressWarnings("unchecked") T canonicalAtom = (T) existingEntry.atom;
                return canonicalAtom;
            }

            // Atom not found, or ID collision with different type.
            // Use computeIfAbsent for atomic insertion.
            Entry finalEntry = atoms.computeIfAbsent(id, key -> {
                Atom atomToStore = atom;
                // Ensure sub-atoms of new expressions are interned recursively *before* storing the parent.
                if (atom instanceof Expression expr) {
                    List<Atom> internedSubs = expr.atoms().stream().map(this::intern).toList();
                    // Only create new Expression object if sub-atoms actually changed during interning.
                    if (!internedSubs.equals(expr.atoms())) {
                        atomToStore = new Expression(internedSubs);
                    }
                }
                // Create the entry for the (potentially reconstructed) atom.
                Entry newEntry = new Entry(atomToStore, new HashMap<>());
                // Add expressions to the index *after* they are fully formed.
                if (atomToStore instanceof Expression exprToIndex) {
                    index.add(exprToIndex);
                }
                stats.inserts.increment(); // Increment insert count.
                return newEntry;
            });

            @SuppressWarnings("unchecked") T canonicalAtom = (T) finalEntry.atom;
            // Handle the case where computeIfAbsent returned an existing entry of the WRONG type.
            if (canonicalAtom.getClass() != atom.getClass()) {
                // This warning indicates a potential logic error where different atom types map to the same ID.
                // Example: "Number" symbol vs. "(Number 1)" expression if ID generation isn't robust.
                // Commented out for cleaner test output, but important for debugging complex cases.
                //                 System.err.println("Warning: ID collision between different Atom types for ID: " + id +
                //                                   ". Found: " + canonicalAtom.getClass().getSimpleName() + " ("+canonicalAtom+"), Tried to intern: " + atom.getClass().getSimpleName() + " ("+atom+")");
            }
            return canonicalAtom;
        }

        /**
         * Queries the index for expressions matching the pattern, considering bindings.
         */
        public List<Expression> query(Expression pattern, Binding binding) {
            stats.queries.increment();
            long start = System.nanoTime();
            // Ensure the pattern uses interned atoms before matching against the index.
            Expression internedPattern = intern(pattern);
            List<Expression> result = index.match(internedPattern, binding, this::intern);
            stats.timeNanos.add(System.nanoTime() - start); // Record query time.
            return result;
        }

        /**
         * Updates metadata for a specific atom.
         */
        public void updateMeta(Atom atom, String key, Object value) {
            Entry entry = atoms.get(atom.id()); // Get entry by atom ID.
            if (entry != null) entry.update(key, value); // Update if found.
        }

        /**
         * Decays priority of all atoms and removes those below a threshold. Rebuilds index after removal.
         */
        public void decayAndForget() {
            double decayFactor = 0.95;
            double removalThreshold = 0.1;
            // Collect IDs to remove to avoid ConcurrentModificationException.
            List<String> toRemove = atoms.entrySet().stream()
                    .filter(entry -> {
                        double newPri = entry.getValue().getPriority() * decayFactor;
                        entry.getValue().update("pri", newPri); // Update priority in-place.
                        return newPri < removalThreshold; // Filter for removal.
                    })
                    .map(Map.Entry::getKey)
                    .toList();

            if (!toRemove.isEmpty()) {
                toRemove.forEach(atoms::remove); // Remove from main atom map.
                optimizeNow(); // Rebuild index to reflect removals.
                // System.out.println("Forgetting " + toRemove.size() + " atoms."); // Optional log
            }
        }

        /**
         * Rebuilds the index if average query time exceeds a threshold.
         */
        public void optimize() {
            double optimizationThresholdMs = 10.0; // Example threshold
            long queryCount = stats.queries.sum();
            // Trigger optimization only after sufficient queries and if threshold is met.
            if (queryCount > 1000 && stats.getAvgQueryTimeMs() > optimizationThresholdMs) {
                optimizeNow();
            }
        }

        /**
         * Forces an immediate rebuild of the Trie index from current atoms.
         */
        public void optimizeNow() {
            // System.out.println("Optimizing memory index (Avg Query Time: " + String.format("%.2f", stats.getAvgQueryTimeMs()) + "ms)...");
            // Collect all current expressions from the atomspace.
            List<Expression> currentExpressions = atoms.values().stream()
                    .map(e -> e.atom)
                    .filter(Expression.class::isInstance)
                    .map(Expression.class::cast)
                    .toList();
            Trie newIndex = new Trie(); // Create a new empty Trie.
            currentExpressions.forEach(newIndex::add); // Populate the new Trie.
            this.index = newIndex; // Atomically replace the old index with the new one.
            stats.resetTime(); // Reset query statistics after optimization.
            stats.optimizeCount++;
            // System.out.println("Optimization complete. Atom count: " + atoms.size());
        }

        /**
         * Simple statistics container. Uses LongAdder for high-contention increments.
         */
        static class Stats {
            final LongAdder queries = new LongAdder();
            final LongAdder inserts = new LongAdder();
            final LongAdder timeNanos = new LongAdder();
            long optimizeCount = 0;

            /**
             * Calculates average query time in milliseconds.
             */
            double getAvgQueryTimeMs() {
                long q = queries.sum(); // Get current sum
                return (q == 0) ? 0 : (timeNanos.sum() / 1_000_000.0) / q;
            }

            /**
             * Resets query count and time, typically after index optimization.
             */
            void resetTime() {
                timeNanos.reset();
                queries.reset();
            }
        }

        // --- Trie Index for Efficient Expression Matching ---
        static class Trie {
            // Child nodes keyed by the ID of the next atom in an expression. Concurrent for thread safety.
            final Map<String, Trie> children = new ConcurrentHashMap<>();
            // Set of complete expressions that terminate at this node. Concurrent for thread safety.
            final Set<Expression> expressions = ConcurrentHashMap.newKeySet();

            Trie() {
            } // Default constructor

            // Constructor to build Trie from an initial collection of expressions.
            Trie(Collection<Expression> exprs) {
                exprs.forEach(this::add);
            }

            /**
             * Performs unification between a pattern and an expression using an iterative approach.
             */
            static Binding unify(Atom pattern, Atom expr, Binding binding) { // Removed unused internFunc parameter
                Deque<Atom> patternStack = new ArrayDeque<>(); // Stack for pattern atoms
                Deque<Atom> exprStack = new ArrayDeque<>();   // Stack for expression atoms
                patternStack.push(pattern);
                exprStack.push(expr);

                Binding currentBinding = binding; // Start with the initial binding

                while (!patternStack.isEmpty()) {
                    // Check if expression stack is unexpectedly empty
                    if (exprStack.isEmpty()) return null; // Pattern has more parts than expression

                    Atom p = currentBinding.apply(patternStack.pop()); // Get pattern atom and apply current binding
                    Atom e = exprStack.pop(); // Get corresponding expression atom

                    if (p.equals(e)) continue; // Atoms are identical, skip

                    switch (p) {
                        case Variable pVar -> { // Pattern is a variable
                            Binding extended = currentBinding.extend(pVar, e); // Try binding it
                            if (extended == null) return null; // Conflict, unification fails
                            currentBinding = extended; // Update binding
                        }
                        case Expression pExpr -> { // Pattern is an expression
                            // Expression must be an expression of the same size
                            if (!(e instanceof Expression eExpr && pExpr.atoms().size() == eExpr.atoms().size()))
                                return null;
                            // Push pairs of elements onto stacks for recursive comparison (in reverse)
                            for (int i = pExpr.atoms().size() - 1; i >= 0; i--) {
                                patternStack.push(pExpr.atoms().get(i));
                                exprStack.push(eExpr.atoms().get(i));
                            }
                        }
                        case Symbol pSym -> { // Pattern is a symbol
                            // Must match expression atom exactly (already checked by initial equals)
                            if (!p.equals(e)) return null; // Symbols must be identical
                        }
                    }
                }

                // Ensure expression stack is also empty - they must have the same structure
                if (!exprStack.isEmpty()) return null;

                return currentBinding; // If loop completes, unification succeeded
            }

            /**
             * Adds an expression to the Trie. Assumes expression atoms are interned.
             */
            void add(Expression expression) {
                Trie node = this;
                // Traverse/create path for each atom in the expression.
                for (Atom atom : expression.atoms()) {
                    // computeIfAbsent ensures atomic node creation if needed.
                    node = node.children.computeIfAbsent(atom.id(), k -> new Trie());
                }
                // Add the complete expression to the set at the terminal node.
                node.expressions.add(expression);
            }

            /**
             * Matches a pattern against the Trie, returning unified expressions.
             */
            List<Expression> match(Expression pattern, Binding initialBinding, Function<Atom, Atom> internFunc) {
                Set<Expression> results = ConcurrentHashMap.newKeySet(); // Thread-safe set for results.
                // Start the recursive matching process.
                matchRecursive(this, pattern, pattern.atoms(), 0, initialBinding, results, internFunc);
                return new ArrayList<>(results); // Return results as a list.
            }

            /**
             * Recursive helper function for Trie matching.
             */
            private void matchRecursive(Trie node, Atom patternExpr, List<Atom> patternAtoms, int pos, Binding currentBinding, Set<Expression> results, Function<Atom, Atom> internFunc) {
                var initialBinding = currentBinding;
                // Base case: Reached the end of the pattern. Check expressions at this node.
                if (pos == patternAtoms.size()) {
                    node.expressions.forEach(expr -> { // For each expression ending here...
                        // Perform a final unification check against the complete pattern.
                        Binding finalBinding = unify(patternExpr, expr, currentBinding); // Removed unused internFunc
                        if (finalBinding != null) { // If unification succeeds...
                            results.add(expr); // Add the matched concrete expression to results.
                        }
                    });
                    return; // End recursion for this path.
                }

                // Get the current pattern atom and apply the binding *before* matching.
                Atom patternAtom = patternAtoms.get(pos);
                Atom boundPatternAtom = currentBinding.apply(patternAtom);

                if (boundPatternAtom instanceof Variable var) {
                    // Pattern atom is an UNBOUND variable: Explore ALL child branches.
                    // Check if variable already has a binding from the *initial* query (less common but possible)
                    Atom initiallyBoundValue = initialBinding.map().get(var);
                    if (initiallyBoundValue != null && !(initiallyBoundValue instanceof Variable)) {
                        // If the variable was bound in the initial query, only follow that path.
                        Trie nextNode = node.children.get(initiallyBoundValue.id());
                        if (nextNode != null) {
                            matchRecursive(nextNode, patternExpr, patternAtoms, pos + 1, currentBinding, results, internFunc);
                        }
                    } else {
                        // Variable is unbound locally, explore all children.
                        node.children.forEach((atomId, nextNode) -> {
                            // Find a concrete atom from the next node to attempt binding.
                            // Simplification: Assume the *ID* represents the atom type adequately for traversal.
                            // The actual unification check later confirms the match.
                            // No binding extension happens here, just traversal.
                            matchRecursive(nextNode, patternExpr, patternAtoms, pos + 1, currentBinding, results, internFunc);
                        });
                    }
                } else {
                    // Pattern atom is GROUND (Symbol, Expression, or bound Variable): Follow specific path.
                    Trie nextNode = node.children.get(boundPatternAtom.id());
                    if (nextNode != null) {
                        // Node exists, continue down this path with the current binding.
                        matchRecursive(nextNode, patternExpr, patternAtoms, pos + 1, currentBinding, results, internFunc);
                    }
                    // If nextNode is null, this path doesn't match the ground pattern atom. Stop recursion here.
                }
            }
        }
    }

    /**
     * Parses S-expression strings into Atoms using the provided Memory for interning.
     */
    static class Parser {
        // Regex to tokenize S-expressions: captures parentheses or sequences of non-space, non-parenthesis characters.
        private static final Pattern TOKEN_PATTERN = Pattern.compile("[()]|[^\\s()]+");

        /**
         * Parses a complete string into a single top-level Atom.
         */
        public static Atom parse(String code, Memory memory) {
            List<String> tokens = tokenize(code);
            if (tokens.isEmpty()) throw new IllegalArgumentException("Cannot parse empty code");
            ParseResult result = parseExpr(tokens, 0, memory);
            // Ensure all tokens were consumed by the parsing process.
            if (result.nextPos != tokens.size()) {
                String extraToken = tokens.get(result.nextPos);
                // Provide more context in the error message
                throw new IllegalArgumentException("Extra token '" + extraToken + "' found after parsing main expression at index "
                        + result.nextPos + " in tokens: " + tokens + " (Parsed: " + result.atom + ")");
            }
            return result.atom; // Return the parsed top-level atom.
        }

        /**
         * Tokenizes the input string based on TOKEN_PATTERN.
         */
        private static List<String> tokenize(String code) {
            List<String> tokens = new ArrayList<>();
            Matcher matcher = TOKEN_PATTERN.matcher(code);
            while (matcher.find()) tokens.add(matcher.group());
            return tokens.stream().filter(s -> !s.isBlank()).toList(); // Filter out any blank tokens.
        }

        /**
         * Recursively parses tokens starting from `pos` into an Atom.
         */
        private static ParseResult parseExpr(List<String> tokens, int pos, Memory memory) {
            if (pos >= tokens.size())
                throw new IllegalArgumentException("Unexpected end of input at token index " + pos + " in tokens: " + tokens);
            String token = tokens.get(pos);

            return switch (token) {
                case "(" -> { // Start of an expression
                    List<Atom> atoms = new ArrayList<>();
                    int currentPos = pos + 1; // Move past '('
                    // Parse elements until ')' is found
                    while (currentPos < tokens.size() && !tokens.get(currentPos).equals(")")) {
                        ParseResult elementResult = parseExpr(tokens, currentPos, memory);
                        atoms.add(elementResult.atom); // Add the parsed sub-atom
                        currentPos = elementResult.nextPos; // Advance position
                    }
                    // Check for closing parenthesis
                    if (currentPos >= tokens.size() || !tokens.get(currentPos).equals(")")) {
                        throw new IllegalArgumentException("Unclosed parenthesis starting at token index " + pos + " in tokens: " + tokens);
                    }
                    // Intern the completed expression and return result
                    yield new ParseResult(memory.intern(new Expression(atoms)), currentPos + 1);
                }
                case ")" -> // Unexpected closing parenthesis
                        throw new IllegalArgumentException("Unexpected closing parenthesis at token index " + pos);
                default -> { // An atomic token (Symbol, Variable, or Number)
                    Atom atom;
                    if (token.startsWith("$") && token.length() > 1) { // Variable
                        atom = new Variable(token.substring(1));
                    } else if (isNumeric(token)) { // Number -> (Number <value>)
                        // Use memory's interned Number symbol
                        atom = new Expression(memory.symNumber, memory.intern(new Symbol(token)));
                    } else { // Symbol
                        atom = new Symbol(token);
                    }
                    // Intern the atomic atom and return result
                    yield new ParseResult(memory.intern(atom), pos + 1);
                }
            };
        }

        /**
         * Checks if a string represents a valid number (integer, decimal, scientific).
         */
        private static boolean isNumeric(String str) {
            if (str == null || str.isEmpty()) return false;
            // Regex allows optional sign, digits, optional decimal part, optional exponent part
            return str.matches("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");
        }

        /**
         * Holds the result of parsing one expression: the atom and the next token position.
         */
        private record ParseResult(Atom atom, int nextPos) {
        }
    }

    // --- Testing ---

    /**
     * Handles calls to external Java methods simulated via '(java_call ...)'.
     */
    static class JVM {
        private final Memory memory; // Reference to memory for interning results
        private final Map<String, BiFunction<JVM, List<Atom>, Atom>> builtins; // Map of built-in functions

        JVM(Memory memory) {
            this.memory = memory;
            this.builtins = buildBuiltinMap(); // Initialize built-in functions map
        }

        /**
         * Executes a 'java_call'. Assumes arguments have already been evaluated.
         */
        public Atom call(String methodName, List<Atom> args) {
            BiFunction<JVM, List<Atom>, Atom> func = builtins.get(methodName); // Lookup function
            if (func == null) { // Handle unknown function call
                System.err.println("Warning: Unknown java_call target: " + methodName + " with args " + args);
                // Return a structured error atom
                return memory.intern(new Expression(Symbol.ERROR_PREFIX, memory.intern(new Symbol("notFound:" + methodName))));
            }
            try { // Execute the function and return result
                return func.apply(this, args);
            } catch (Exception e) { // Handle errors during execution
                System.err.println("Error during java_call '" + methodName + "': " + e.getMessage());
                e.printStackTrace(); // Print stack trace for debugging help
                return memory.intern(new Expression(Symbol.ERROR_PREFIX, memory.intern(new Symbol(e.getClass().getSimpleName()))));
            }
        }

        /**
         * Converts various Java objects back into Cog Atoms, interning them.
         */
        public Atom toAtom(Object value) {
            return switch (value) {
                case null -> memory.symVoid; // Null -> void symbol
                case Atom a -> a; // Already an Atom (should be interned by caller if needed)
                case Boolean b -> b ? memory.symTrue : memory.symFalse; // Boolean -> true/false symbols
                // Convert various Number types to BigDecimal, then to (Number <value>) expression
                case BigDecimal n ->
                        memory.intern(new Expression(memory.symNumber, memory.intern(new Symbol(n.stripTrailingZeros().toPlainString())))); // Standard number representation
                case Double d -> toAtom(BigDecimal.valueOf(d));
                case Integer i -> toAtom(BigDecimal.valueOf(i));
                case Long l -> toAtom(BigDecimal.valueOf(l));
                case Number n -> toAtom(new BigDecimal(n.toString())); // Fallback for other Number types
                case String s -> memory.intern(new Symbol(s)); // String -> Symbol
                // Convert List/Array of Atoms to Expression
                case List<?> lst when !lst.isEmpty() && lst.get(0) instanceof Atom ->
                        memory.intern(new Expression(lst.stream().map(Atom.class::cast).toList()));
                case List<?> lst when lst.isEmpty() ->
                        memory.intern(new Expression(List.of())); // Empty list expression
                case Object[] arr when arr.length > 0 && arr[0] instanceof Atom ->
                        memory.intern(new Expression(Stream.of(arr).map(Atom.class::cast).toList()));
                case Object[] arr when arr.length == 0 ->
                        memory.intern(new Expression(List.of())); // Empty array expression
                // Default: Convert unknown object to Symbol via its toString() method
                default -> memory.intern(new Symbol(value.toString()));
            };
        }

        /**
         * Helper to safely extract a BigDecimal value from a (Number <val>) atom.
         */
        private BigDecimal getNumberArg(Atom atom, int index, BigDecimal defaultVal) {
            if (atom instanceof Expression e
                    && e.atoms().size() == 2
                    && e.atoms().get(0).equals(memory.symNumber) // Check head is 'Number' symbol
                    && e.atoms().get(1) instanceof Symbol valSym) {
                try { // Try parsing the symbol's name as BigDecimal
                    return new BigDecimal(valSym.name());
                } catch (NumberFormatException ignored) { // Log error on invalid format
                    // System.err.println("Warning: Invalid number format in arg " + index + ": " + valSym.name());
                }
            }
            // Return default value if not a valid Number expression
            return defaultVal;
        }

        // --- Built-in Function Implementations ---

        /**
         * Creates and returns the map of built-in functions callable via 'java_call'.
         */
        private Map<String, BiFunction<JVM, List<Atom>, Atom>> buildBuiltinMap() {
            Map<String, BiFunction<JVM, List<Atom>, Atom>> map = new HashMap<>();
            final BigDecimal ZERO = BigDecimal.ZERO; // Constant for zero

            // Math Operations using BigDecimal for precision
            map.put("Math.add", (jvm, args) -> jvm.toAtom(args.size() < 2 ? ZERO : getNumberArg(args.get(0), 0, ZERO).add(getNumberArg(args.get(1), 1, ZERO))));
            map.put("Math.subtract", (jvm, args) -> jvm.toAtom(args.size() < 2 ? ZERO : getNumberArg(args.get(0), 0, ZERO).subtract(getNumberArg(args.get(1), 1, ZERO))));
            map.put("Math.multiply", (jvm, args) -> jvm.toAtom(args.size() < 2 ? ZERO : getNumberArg(args.get(0), 0, ZERO).multiply(getNumberArg(args.get(1), 1, ZERO))));
            map.put("Math.divide", (jvm, args) -> { // Handle division by zero
                if (args.size() < 2) return jvm.toAtom(ZERO);
                BigDecimal num = getNumberArg(args.get(0), 0, ZERO);
                BigDecimal divisor = getNumberArg(args.get(1), 1, ZERO);
                return divisor.compareTo(ZERO) == 0
                        ? jvm.memory.intern(new Expression(Symbol.ERROR_PREFIX, memory.intern(new Symbol("divByZero")))) // Error atom
                        : jvm.toAtom(num.divide(divisor, 10, RoundingMode.HALF_UP)); // Specify scale and rounding
            });
            map.put("Math.greaterThan", (jvm, args) -> jvm.toAtom(args.size() >= 2 && getNumberArg(args.get(0), 0, ZERO).compareTo(getNumberArg(args.get(1), 1, ZERO)) > 0));
            map.put("Math.lessThan", (jvm, args) -> jvm.toAtom(args.size() >= 2 && getNumberArg(args.get(0), 0, ZERO).compareTo(getNumberArg(args.get(1), 1, ZERO)) < 0));
            map.put("Math.random", (jvm, args) -> jvm.toAtom(Math.random())); // Return random double as (Number <val>)

            // Console Output
            map.put("console.log", (jvm, args) -> { // Log arguments to console
                String output = args.stream().map(a -> switch (a) { // Format numbers nicely
                    case Expression e when e.atoms().size() == 2 && e.atoms().get(0).equals(jvm.memory.symNumber) ->
                            e.atoms().get(1).id();
                    default -> a.toString();
                }).collect(Collectors.joining(" "));
                System.out.println("LOG: " + output);
                return jvm.memory.symVoid; // Logging returns void
            });

            // Object/Atom Operations
            map.put("Object.equals", (jvm, args) -> jvm.toAtom(args.size() >= 2 && Objects.equals(args.get(0), args.get(1)))); // Equality check
            map.put("Object.toString", (jvm, args) -> jvm.toAtom(args.isEmpty() ? "null" : args.get(0).toString())); // Simple toString

            // Type Checking
            map.put("Atom.isSymbol", (jvm, args) -> jvm.toAtom(!args.isEmpty() && args.get(0) instanceof Symbol));
            map.put("Atom.isVariable", (jvm, args) -> jvm.toAtom(!args.isEmpty() && args.get(0) instanceof Variable));
            map.put("Atom.isExpression", (jvm, args) -> jvm.toAtom(!args.isEmpty() && args.get(0) instanceof Expression));

            // List/Expression Operations (using helper functions for brevity)
            Function<List<Atom>, Atom> getExprArg = (args) -> args.isEmpty() ? null : args.get(0);
            Function<List<Atom>, List<Atom>> getAtomList = (args) -> {
                Atom a = getExprArg.apply(args);
                return (a instanceof Expression e) ? e.atoms() : List.of();
            };

            map.put("Expression.atoms", (jvm, args) -> jvm.toAtom(getAtomList.apply(args))); // Get list of atoms
            map.put("Expression.length", (jvm, args) -> jvm.toAtom(BigDecimal.valueOf(getAtomList.apply(args).size()))); // Get length as (Number N)
            map.put("Expression.isEmpty", (jvm, args) -> jvm.toAtom(getAtomList.apply(args).isEmpty())); // Check if empty
            map.put("Expression.head", (jvm, args) -> {
                List<Atom> a = getAtomList.apply(args);
                return a.isEmpty() ? jvm.memory.symVoid : a.get(0);
            }); // Get first element
            map.put("Expression.tail", (jvm, args) -> {
                List<Atom> a = getAtomList.apply(args);
                return jvm.toAtom(a.isEmpty() ? List.of() : a.subList(1, a.size()));
            }); // Get list without first element
            map.put("Expression.nth", (jvm, args) -> { // Get element at index N
                List<Atom> a = getAtomList.apply(args);
                if (args.size() < 2 || a.isEmpty()) return jvm.memory.symVoid;
                int index = getNumberArg(args.get(1), 1, BigDecimal.valueOf(-1)).intValue(); // Get index from (Number N) arg
                return (index < 0 || index >= a.size()) ? jvm.memory.symVoid : a.get(index); // Return void if index out of bounds
            });
            map.put("Expression.cons", (jvm, args) -> { // Prepend an atom to a list expression
                if (args.size() < 2 || !(args.get(1) instanceof Expression tailExpr))
                    return jvm.memory.intern(new Expression(Symbol.ERROR_PREFIX, memory.intern(new Symbol("consRequiresExpr"))));
                List<Atom> combined = Stream.concat(Stream.of(args.get(0)), tailExpr.atoms().stream()).toList();
                return jvm.memory.intern(new Expression(combined)); // Intern the new list expression
            });

            return Collections.unmodifiableMap(map); // Return immutable map
        }
    }

    /**
     * Represents an autonomous agent within the Cog system.
     */
    class Agent {
        final String id; // Unique agent identifier.
        volatile boolean running = true; // Flag to control the agent's execution loop.

        Agent(String id) {
            this.id = id;
        }

        /**
         * Performs one step of the agent's behavior cycle by evaluating its behavior rule.
         */
        public Atom step() {
            if (!running) return memory.symVoid; // Return void if stopped.
            // Construct the behavior query: (agent_behavior <agent_id>)
            Atom behaviorQuery = memory.intern(new Expression(memory.intern(new Symbol("agent_behavior")), memory.intern(new Symbol(id))));
            // Evaluate the query using the main evaluation engine.
            return evaluate(behaviorQuery);
        }

        /**
         * Starts the agent's autonomous execution loop using the scheduled executor.
         */
        void startAutonomousLoop() {
            Runnable task = () -> { // Define the task for one agent step and rescheduling.
                if (!running) return; // Exit if stopped.
                try {
                    step(); // Execute one behavior step.
                    // Determine rescheduling delay based on agent's priority metadata (or default).
                    double priority = 1.0;
                    Atom behaviorAtom = memory.intern(new Expression(memory.intern(new Symbol("agent_behavior")), memory.intern(new Symbol(id))));
                    Entry entry = memory.atoms.get(behaviorAtom.id());
                    if (entry != null) priority = entry.getPriority();
                    // Calculate delay (ms), ensuring minimum delay and handling zero/negative priority.
                    long delayMillis = Math.max(50, (long) (1000 / Math.max(0.1, priority)));

                    if (running) { // Reschedule if still running.
                        // Use compute to replace existing scheduled task if any.
                        scheduledTasks.compute("agent-" + id, (k, oldFuture) ->
                                executor.schedule(this::startAutonomousLoop, delayMillis, TimeUnit.MILLISECONDS));
                    }
                } catch (RejectedExecutionException e) { // Handle task rejection (e.g., during shutdown).
                    if (!executor.isShutdown()) System.err.println("Agent " + id + " task rejected.");
                    running = false; // Stop agent if task cannot be scheduled.
                } catch (Exception e) { // Catch unexpected errors during agent step.
                    System.err.println("Error in agent " + id + " loop: " + e.getMessage());
                    e.printStackTrace(); // Log error details.
                    // Optionally stop the agent on error: running = false;
                }
            };
            // Schedule the first execution of the task shortly after startup.
            // Use computeIfAbsent to avoid scheduling multiple times if startAutonomousLoop is called again.
            scheduledTasks.computeIfAbsent("agent-" + id, k -> executor.schedule(task, 50, TimeUnit.MILLISECONDS));
        }

        /**
         * Signals the agent to stop its autonomous loop.
         */
        void stop() {
            running = false; // Set running flag to false.
            ScheduledFuture<?> future = scheduledTasks.remove("agent-" + id); // Remove scheduled task.
            if (future != null) future.cancel(false); // Cancel future execution (don't interrupt if running).
        }
    }
}