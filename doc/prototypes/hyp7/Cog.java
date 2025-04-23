package dumb.hyp7;


import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Optional.empty;

/**
 * Cog - Cognitive Logic Engine (Synthesized Hyperon-Inspired Iteration v6.2 - Concurrent Agents)
 * <p>
 * A unified, self-aware (via homoiconicity & reflection), and recursively self-improving
 * cognitive architecture implementing a Hyperon/MeTTa-like symbolic system.
 * It integrates parsing, pattern matching, rule-based rewriting, probabilistic reasoning,
 * attention mechanisms, agent interaction, and JVM reflection/MethodHandles within
 * a single, consolidated Java class structure.
 * <p>
 * ## Key Enhancements in v6.2:
 * - **Concurrent Multi-Agent Support:** Manages multiple `Agent` instances, each associated with a `Game` environment, running concurrently via an `AgentScheduler`.
 * - **Priority-Based Scheduling:** The `AgentScheduler` allocates execution steps based on the MeTTa-defined priority (`Pri`) of the agent's symbolic representation in `Mem`. Higher priority agents get more frequent execution turns.
 * - **System Processes as Agents:** Demonstrates how internal system functions (like memory maintenance) can be modeled as a `Game` played by a dedicated system `Agent` (e.g., `MaintenanceAgent`), enabling self-regulation through the core cognitive mechanisms.
 * - **Refined Agent Interaction:** Agent-related `Is` functions (`AgentPerceive`, `AgentExecute`, etc.) now take the agent's ID (`Sym`) as the first argument to correctly route actions to the associated `Game`.
 * <p>
 * ## Core Design Pillars (Unchanged):
 * - Homoiconicity, Unified Representation (Atoms), Memory (Mem), Metadata (Value), MeTTa Syntax/Parsing, MeTTa Execution (Interp), Bridged Primitives (Is), Probabilistic/Priority Driven, JVM Integration (Jvm), Modern Java.
 * <p>
 * ## Self-Improvement & MeTTa Control Pathways:
 * 1.  **Interpreter Strategy (`Interp.evalRecursive`):** Candidate for MeTTa meta-rules.
 * 2.  **JVM Integration (`Core.initIsFn`, `Jvm`):** Implemented (`Java*` functions).
 * 3.  **Agent Control Flow (`Agent.runCycle`, `AgentScheduler`):** Partially Implemented. Agent step logic via `AgentStep` eval. Scheduler logic is Java, but priorities are MeTTa-driven. System agents run via the same mechanism.
 * 4.  **Configuration Management:** Candidate for MeTTa-driven parameters (e.g., thresholds, agent behavior params).
 *
 * @version 6.2 - Concurrent Agents, Priority Scheduling, System Agents
 */
public final class Cog {

    // --- Configuration Constants ---
    // (Some marked as candidates for MeTTa-driven config)
    private static final double TRUTH_DEFAULT_SENSITIVITY = 1.0;
    private static final double TRUTH_MIN_CONFIDENCE_MATCH = 0.01; // Candidate
    private static final double TRUTH_REVISION_CONFIDENCE_THRESHOLD = 0.1; // Candidate
    private static final float PRI_INITIAL_STI = 0.2f;
    private static final float PRI_INITIAL_LTI_FACTOR = 0.1f;
    private static final float PRI_STI_DECAY_RATE = 0.08f;
    private static final float PRI_LTI_DECAY_RATE = 0.008f;
    private static final float PRI_STI_TO_LTI_RATE = 0.02f;
    private static final float PRI_ACCESS_BOOST = 0.08f;
    private static final float PRI_BOOST_ON_REVISION_MAX = 0.5f;
    private static final float PRI_BOOST_ON_GOAL_FOCUS = 0.95f; // Candidate
    private static final float PRI_BOOST_ON_PERCEPTION = 0.75f; // Candidate
    private static final float PRI_MIN_FORGET_THRESHOLD = 0.015f; // Candidate
    private static final long MAINTENANCE_INTERVAL_MS = 10000; // System Agent (Maintenance) interval trigger
    private static final int MEM_CAPACITY_DEFAULT = 18000; // Candidate
    private static final int FORGETTING_TARGET_MEM_SIZE_FACTOR = 80; // Candidate (%)
    private static final int INTERPRETER_DEFAULT_MAX_DEPTH = 15; // Candidate
    private static final int INTERPRETER_MAX_RESULTS = 50; // Candidate
    private static final long AGENT_SCHEDULER_INTERVAL_MS = 100; // How often scheduler checks priorities
    private static final int AGENT_SCHEDULER_STEPS_PER_INTERVAL = 5; // Max agent steps executed per scheduler interval

    private static final String VAR_PREFIX = "$";
    // Symbols protected from forgetting (core syntax, types, essential Is functions, agent concepts)
    private static final Set<String> PROTECTED_SYMBOLS = Set.of(
        // Core syntax and types
        "=", ":", "->", "Type", "True", "False", "Nil", "Number", "Bool", "String", "JavaObject",
        "Self", "Goal", "State", "Action", "Utility", "Implies", "Seq", "Act", "Atom", "List", "Fn", // Added missing types
        // Core Is operations
        "match", "eval", "add-atom", "remove-atom", "get-value", "&self", "get-atoms", "get-agent-game", // Added system Is
        // Control
        "If", "Let", "IsEmpty", "IsNil",
        // Arithmetic
        "_+", "_-", "_*", "_/", "+", "-", "*", "/", "RandomFloat",
        // Comparison
        "_==", "_>", "_<", "==", ">", "<",
        // List / String
        "Concat", "RandomChoice", "FirstNumOrZero", "First",
        // Agent operations
        "AgentStep", "AgentPerceive", "AgentAvailableActions", "AgentExecute", "AgentLearn", "CheckGoal", "UpdateUtility",
        // System Agent concepts
        "MaintenanceAgent", "MaintenanceStatus", "PerformMaintenance", // System Agent Example
        // JVM Integration
        "JavaNew", "JavaCall", "JavaStaticCall", "JavaField", "JavaProxy"
    );

    // --- Core Symbols (Canonical instances initialized in Core) ---
    public static Atom SYM_EQ, SYM_COLON, SYM_ARROW, SYM_TYPE, SYM_TRUE, SYM_FALSE, SYM_SELF, SYM_NIL, SYM_MAINT_AGENT;

    public final Mem mem;
    public final Unify unify;
    private final Interp interp;
    private final MettaParser parser;
    private final ScheduledExecutorService systemScheduler; // For maintenance, agent scheduling trigger
    private final AgentScheduler agentScheduler; // Manages concurrent agents
    private final AtomicLong logicalTime = new AtomicLong(0);
    private final Jvm jvm; // JVM Bridge

    public Cog() {
        this.mem = new Mem(this::getLogicalTime, MEM_CAPACITY_DEFAULT);
        this.unify = new Unify(this.mem);
        this.interp = new Interp(this);
        this.parser = new MettaParser(this);
        // Use a shared scheduler for maintenance and agent scheduling ticks
        this.systemScheduler = Executors.newScheduledThreadPool(2, r -> { // Need 2 threads: one for maint, one for agent scheduler ticks
            var t = new Thread(r, "Cog-System");
            t.setDaemon(true); // Allow JVM exit
            return t;
        });
        this.agentScheduler = new AgentScheduler(this);
        this.jvm = new Jvm(this); // Initialize the JVM bridge before Core uses its functions
        new Core(this); // Initializes symbols, Is functions, core rules, loads bootstrap MeTTa

        // Schedule periodic agent scheduling cycle
        systemScheduler.scheduleAtFixedRate(this.agentScheduler::runSchedulingCycle, AGENT_SCHEDULER_INTERVAL_MS, AGENT_SCHEDULER_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // Initialize and register the maintenance agent
        var maintGame = new MaintenanceGame(this);
        var maintGoal = E(S("MaintenanceStatus"), S("Optimal")); // Example goal
        this.registerAgent(SYM_MAINT_AGENT, maintGame, maintGoal, -1); // Runs indefinitely (-1 cycles)
        mem.updateTruth(SYM_MAINT_AGENT, Truth.TRUE); // Ensure it has high truth
        mem.boost(SYM_MAINT_AGENT, 0.6f); // Give it reasonable initial priority

        System.out.println("Cog (v6.2 Concurrent Agents) Initialized. Init Size: " + mem.size());
    }

    /** Clamp a double value between 0.0 and 1.0. */
    public static double unitize(double v) { return Math.max(0, Math.min(1, v)); }
    /** Clamp a float value between 0.0 and 1.0. */
    public static float unitize(float v) { return Math.max(0, Math.min(1f, v)); }

    // --- Main Demonstration (Illustrates multi-agent and system agent capabilities) ---
    public static void main(String[] args) {
        var cog = new Cog();
        try {
            System.out.println("\n--- Cog Synthesized Test Suite (v6.2) ---");
            // Sections 1-5: Parsing, Unification, Evaluation, Query, Metaprogramming (remain largely the same)
            printSectionHeader(1,"Parsing & Basic Eval");
            evaluateAndPrint(cog, "Simple Math", cog.parse("(* (+ 2.0 3.0) 4.0)"));

            printSectionHeader(2,"Query & Unification");
            cog.load("(Knows Alice Bob)\n(Knows Bob Charlie)");
            evaluateAndPrint(cog, "Query Knows", cog.parse("(match &self (Knows $x $y) ($x $y))"));

            // --- [6] Multi-Agent Simulation ---
            printSectionHeader(6, "Multi-Agent Simulation");
            // Agent 1: SimpleGame - tries to reach goal
            var game1 = new SimpleGame(cog, "Sim1"); // Give game instance a name/ID
            var agent1Id = cog.S("Agent_Sim1");
            var agent1Goal = cog.E(cog.S("State"), agent1Id, cog.S(game1.goalStateName())); // Agent-specific goal state
            cog.registerAgent(agent1Id, game1, agent1Goal, 15); // Max 15 cycles
            System.out.println("Registered Agent: " + agent1Id + " with Goal: " + agent1Goal);
            cog.mem.boost(agent1Id, 0.8f); // Give agent 1 higher initial priority

            // Agent 2: Another SimpleGame, maybe slightly different goal or params
            var game2 = new SimpleGame(cog, "Sim2");
            var agent2Id = cog.S("Agent_Sim2");
            var agent2Goal = cog.E(cog.S("State"), agent2Id, cog.S(game2.goalStateName()));
            cog.registerAgent(agent2Id, game2, agent2Goal, 20);
            System.out.println("Registered Agent: " + agent2Id + " with Goal: " + agent2Goal);
            cog.mem.boost(agent2Id, 0.5f); // Lower priority

            // Let the scheduler run agents for a while
            System.out.println("\nStarting agent simulation (run concurrently by scheduler)...");
            Thread.sleep(AGENT_SCHEDULER_INTERVAL_MS * 12); // Run for ~12 scheduler intervals

            System.out.println("\nChecking agent states after simulation time:");
            printAgentStatus(cog, agent1Id);
            printAgentStatus(cog, agent2Id);
            printAgentStatus(cog, SYM_MAINT_AGENT); // Check maintenance agent too

            // --- [7] JVM Integration ---
            printSectionHeader(7, "JVM Integration (Unchanged)");
            evaluateAndPrint(cog, "Java Static Math.max", cog.parse("(JavaStaticCall \"java.lang.Math\" \"max\" 5.0 10.0)"));
            evaluateAndPrint(cog, "Java Instance Call (List)",
                    cog.parse("(Let (= $list (JavaNew \"java.util.ArrayList\")) " +
                            "     (JavaCall $list \"add\" \"item1\") " +
                            "     (JavaCall $list \"size\"))"));

            // --- [8] System Agent (Maintenance) Check ---
            printSectionHeader(8, "System Agent Check (Maintenance)");
            System.out.println("Maintenance agent runs periodically. Check memory size changes over time.");
            var sizeBefore = cog.mem.size();
            System.out.println("Memory size before waiting: " + sizeBefore);
            System.out.println("Waiting for maintenance agent cycles...");
            Thread.sleep(MAINTENANCE_INTERVAL_MS + AGENT_SCHEDULER_INTERVAL_MS * 5); // Wait for potential maintenance run
            var sizeAfter = cog.mem.size();
            System.out.println("Memory size after waiting: " + sizeAfter + " (Maintenance agent attempts reduction if size > " + cog.mem.capacity + ")");
            printAgentStatus(cog, SYM_MAINT_AGENT); // Show final status

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

    private static void evaluateAndPrint(Cog cog, String name, Atom expr) {
        System.out.println("eval \"" + name + "\" \t " + expr);
        var results = cog.eval(expr);
        System.out.printf(" -> [%s]%n", results.stream().map(Atom::toString).collect(Collectors.joining(", ")));
    }

    private static void printAgentStatus(Cog cog, Atom agentId) {
        var context = cog.agentScheduler.getAgentContext(agentId);
        if (context == null) { System.out.println("Status Agent " + agentId + ": Not Registered."); return; }
        var pri = cog.mem.valueOrDefault(agentId).pri;
        var goalMet = context.isGoalMet.get();
        var cycles = context.cyclesCompleted.get();
        var state = context.lastStateAtom.get();
        System.out.printf("Status Agent %s: Priority(STI=%.3f, LTI=%.3f), GoalMet=%s, Cycles=%d/%d, LastState=%s%n",
                agentId, pri.sti, pri.lti, goalMet, cycles, context.maxCycles, state != null ? state.toString() : "N/A");
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
    public Expr EQ(Atom premise, Atom conclusion) { return add(E(SYM_EQ, premise, conclusion)); }
    public Expr TYPE(Atom instance, Atom type) { return add(E(SYM_COLON, instance, type)); }
    public List<Atom> eval(Atom expr) { return interp.eval(expr, INTERPRETER_DEFAULT_MAX_DEPTH); }
    public List<Atom> eval(Atom expr, int maxDepth) { return interp.eval(expr, maxDepth); }
    public Optional<Atom> evalBest(Atom expr) { return eval(expr).stream().max(Comparator.comparingDouble(a -> mem.valueOrDefault(a).getWeightedTruth())); }
    public List<Answer> query(Atom pattern) { return mem.query(pattern); }

    /** Registers an agent with the scheduler. */
    public void registerAgent(Atom agentId, Game game, Atom goalPattern, int maxCycles) {
        agentScheduler.addAgent(agentId, game, goalPattern, maxCycles);
    }

    /** Unregisters an agent from the scheduler. */
    public void unregisterAgent(Atom agentId) {
        agentScheduler.removeAgent(agentId);
    }

    /** Shuts down the background system scheduler and agent scheduler. */
    public void shutdown() {
        agentScheduler.stop(); // Stop agent execution first
        systemScheduler.shutdown();
        try {
            if (!systemScheduler.awaitTermination(1, TimeUnit.SECONDS)) systemScheduler.shutdownNow();
        } catch (InterruptedException e) {
            systemScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        System.out.println("Cog schedulers shut down.");
    }

    // Internal method for system agent game access, used by Is functions
    Optional<Game> getGameForAgent(Atom agentId) {
        return agentScheduler.getGameForAgent(agentId);
    }

    // --- Core Data Structures (Atom, Sym, Var, Expr, Is, Value, Truth, Pri, Time) ---
    // (These remain largely unchanged from v6.1, omitting for brevity - see previous version)
    // ... [Omitted: Atom, Sym, Var, Expr, Is records] ...
    // ... [Omitted: Value, Truth, Pri, Time records] ...
        /** Represents the type of an Atom. */
    public enum AtomType { SYM, VAR, EXPR, IS }

    /** Base sealed interface for all symbolic atoms in the Cog system. Immutable. */
    public sealed interface Atom {
        String id(); AtomType type();
        default Sym asSym() { return (Sym) this; } default Var asVar() { return (Var) this; }
        default Expr asExpr() { return (Expr) this; } default Is<?> asIs() { return (Is<?>) this; }
        @Override boolean equals(Object other); @Override int hashCode(); @Override String toString();
    }

    /** Symbol atom (named constant). */
    public record Sym(String name) implements Atom {
        private static final ConcurrentMap<String, Sym> SYMBOL_CACHE = new ConcurrentHashMap<>();
        public static Sym of(String name) { return SYMBOL_CACHE.computeIfAbsent(name, Sym::new); }
        @Override public String id() { return name; } @Override public AtomType type() { return AtomType.SYM; }
        @Override public String toString() { return name; } @Override public int hashCode() { return name.hashCode(); }
        @Override public boolean equals(Object o) { return this == o || (o instanceof Sym s && name.equals(s.name)); }
    }

    /** Variable atom (used in patterns/rules). */
    public record Var(String name) implements Atom {
        public Var { if (name.startsWith(VAR_PREFIX)) throw new IllegalArgumentException("Var name excludes prefix"); }
        @Override public String id() { return VAR_PREFIX + name; } @Override public AtomType type() { return AtomType.VAR; }
        @Override public String toString() { return id(); } @Override public int hashCode() { return id().hashCode(); }
        @Override public boolean equals(Object o) { return this == o || (o instanceof Var v && id().equals(v.id())); }
    }

    /** Expression atom (sequence of other atoms). */
    public record Expr(String id, List<Atom> children) implements Atom {
        private static final ConcurrentMap<List<Atom>, String> idCache = new ConcurrentHashMap<>();
        public Expr(List<Atom> inputChildren) { this(computeId(List.copyOf(inputChildren)), List.copyOf(inputChildren)); }
        public Expr(String id, List<Atom> children) { this.id = id; this.children = children; }
        private static String computeId(List<Atom> childList) { return idCache.computeIfAbsent(childList, Expr::computeIdInternal); }
        private static String computeIdInternal(List<Atom> childList) { return "(" + childList.stream().map(Atom::id).collect(Collectors.joining(" ")) + ")"; }
        @Override public AtomType type() { return AtomType.EXPR; }
        public @Nullable Atom head() { return children.isEmpty() ? null : children.getFirst(); }
        public List<Atom> tail() { return children.isEmpty() ? emptyList() : children.subList(1, children.size()); }
        @Override public String toString() { return id; } @Override public int hashCode() { return id.hashCode(); }
        @Override public boolean equals(Object o) { return this == o || (o instanceof Expr ea && id.equals(ea.id)); }
    }

    /** Grounded/Interpreted Symbol atom (bridges to Java objects/functions). */
    public record Is<T>(String id, @Nullable T value, @Nullable Function<List<Atom>, Optional<Atom>> fn) implements Atom {
        public Is(T value) { this(deriveId(value), value, null); }
        public Is(String id, T value) { this(id, value, null); }
        public Is(String name, Function<List<Atom>, Optional<Atom>> fn) { this(name, null, fn); }

        private static <T> String deriveId(T value) {
            if (value == null) return "is:null:null"; var typeName = value.getClass().getSimpleName(); String valuePart;
            if (value instanceof String s) { typeName = "String"; valuePart = s.length() < 30 ? s : s.substring(0, 27) + "..."; }
            else if (value instanceof Double d) { typeName = "Double"; valuePart = d.toString(); }
            else if (value instanceof Integer i) { typeName = "Integer"; valuePart = i.toString();}
            else if (value instanceof Boolean b) { typeName = "Boolean"; valuePart = b.toString(); }
            else if (value instanceof Atom a) { typeName = "Atom"; valuePart = a.id(); }
            else if (value instanceof List<?> list) { typeName = "List"; valuePart = "[" + list.stream().map(o -> o instanceof Atom a ? a.id() : String.valueOf(o)).limit(3).collect(Collectors.joining(",")) + (list.size() > 3 ? ",..." : "") + "]"; }
            else { var valStr = String.valueOf(value); valuePart = (valStr.length() < 30 && !valStr.contains("@")) ? valStr : value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value)); }
            return "is:" + typeName + ":" + valuePart;
        }
        @Override public AtomType type() { return AtomType.IS; }
        public boolean isFn() { return fn != null; }
        public Optional<Atom> apply(List<Atom> args) { return isFn() ? fn.apply(args) : empty(); }
        @Override public String toString() {
            if (isFn()) return "IsFn<" + id + ">";
            if (value instanceof String s) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\"";
            if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
            if (value instanceof Atom a) return a.toString();
            if (value instanceof List<?> list && !list.isEmpty() && list.stream().allMatch(o -> o instanceof Atom)) {
                 return "Is<List:" + list.stream().map(Object::toString).limit(5).collect(Collectors.joining(",", "[", list.size() > 5 ? ",...]" : "]")) + ">";
            }
            return id; // Default representation
        }
        @Override public int hashCode() { return id.hashCode(); }
        @Override public boolean equals(Object o) { return this == o || (o instanceof Is<?> ga && id.equals(ga.id)); }
    }

    /** Immutable record holding metadata associated with an Atom. */
    public record Value(Truth truth, Pri pri, Time access) {
        public static final Value DEFAULT = new Value(Truth.UNKNOWN, Pri.DEFAULT, Time.DEFAULT);
        public Value withTruth(Truth nt) { return new Value(nt, pri, access); }
        public Value withPri(Pri np) { return new Value(truth, np, access); }
        public Value withTime(Time na) { return new Value(truth, pri, na); }
        public Value updateTime(long now) { return new Value(truth, pri, new Time(now)); }
        public Value boost(float amount, long now) { return new Value(truth, pri.boost(amount), new Time(now)); }
        public Value decay(long now) { return new Value(truth, pri.decay(), new Time(now)); }
        public double getCurrentPri(long now) {
            var timeSinceAccess = Math.max(0, now - access.time());
            var recencyFactor = Math.exp(-timeSinceAccess / (double)(MAINTENANCE_INTERVAL_MS * 3)); // Decay over ~3 maint cycles
            return pri.getCurrent(recencyFactor) * truth.confidence();
        }
        public double getWeightedTruth() { return truth.strength * truth.confidence(); }
        @Override public String toString() { return truth + " " + pri + " " + access; }
    }

    /** Immutable record representing probabilistic truth (strength and confidence/count). */
    public record Truth(double strength, double count) {
        public static final Truth TRUE = new Truth(1.0, 10.0); public static final Truth FALSE = new Truth(0.0, 10.0);
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

    /** Immutable record representing priority (Short-Term Importance and Long-Term Importance). */
    public record Pri(float sti, float lti) {
        public static final Pri DEFAULT = new Pri(PRI_INITIAL_STI, PRI_INITIAL_STI * PRI_INITIAL_LTI_FACTOR);
        public Pri { sti = unitize(sti); lti = unitize(lti); }
        public Pri decay() {
            var decayedSti = sti * (1 - PRI_STI_DECAY_RATE);
            var stiToLtiTransfer = sti * PRI_STI_DECAY_RATE * PRI_STI_TO_LTI_RATE;
            var decayedLti = lti * (1 - PRI_LTI_DECAY_RATE) + stiToLtiTransfer;
            return new Pri(unitize(decayedSti), unitize(decayedLti));
        }
        public Pri boost(float amount) {
            if (amount <= 0) return this; var boostedSti = unitize(sti + amount);
            var stiGain = boostedSti - sti; var ltiBoostFactor = PRI_STI_TO_LTI_RATE * amount;
            var boostedLti = unitize(lti + stiGain * ltiBoostFactor);
            return new Pri(boostedSti, boostedLti);
        }
        public double getCurrent(double recencyFactor) { return sti * recencyFactor * 0.6 + lti * 0.4; }
        @Override public String toString() { return String.format("$%.3f,%.3f", sti, lti); }
    }

    /** Immutable record representing logical time (access timestamp). */
    public record Time(long time) {
        public static final Time DEFAULT = new Time(0L); @Override public String toString() { return "@" + time; }
    }


    // --- Core Engine Components ---

    /** Manages Atom storage, indexing, metadata (Value), and forgetting mechanism. */
    public static class Mem {
        private final ConcurrentMap<String, Atom> atomsById = new ConcurrentHashMap<>(1024);
        private final ConcurrentMap<Atom, AtomicReference<Value>> atoms = new ConcurrentHashMap<>(1024);
        private final ConcurrentMap<Atom, Set<String>> indexByHead = new ConcurrentHashMap<>();
        private final Supplier<Long> timeSource;
        private final int capacity;
        private final AtomicBoolean maintenanceRunning = new AtomicBoolean(false); // Prevent concurrent maintenance

        public Mem(Supplier<Long> timeSource, int capacity) { this.timeSource = timeSource; this.capacity = capacity; }

        /** Adds an atom, ensuring canonical instance and initializes/updates metadata. */
        public <A extends Atom> A add(A atom) {
            var canonicalAtom = atomsById.computeIfAbsent(atom.id(), id -> atom);
            var now = timeSource.get();
            atoms.computeIfAbsent(canonicalAtom, k -> {
                var initVal = Value.DEFAULT.withPri(Pri.DEFAULT).updateTime(now);
                updateIndices(k); // Index expressions when first added
                return new AtomicReference<>(initVal);
            }).updateAndGet(v -> v.boost(PRI_ACCESS_BOOST * 0.1f, now)); // Slight boost on add/access
            // Note: Memory check/forgetting is now triggered by the MaintenanceAgent
            return (A) canonicalAtom; // Return canonical instance
        }

        public Optional<Atom> atom(String id) { return Optional.ofNullable(atomsById.get(id)).map(this::access); }
        public Optional<Atom> atom(Atom atom) { return Optional.ofNullable(atomsById.get(atom.id())).map(this::access); }
        public Optional<Value> value(Atom atom) { return atom(atom).map(atoms::get).map(AtomicReference::get); }
        private Atom access(Atom atom) { updateValue(atom, v -> v.boost(PRI_ACCESS_BOOST, timeSource.get())); return atom; }
        public Value valueOrDefault(Atom atom) { return value(atom).orElse(Value.DEFAULT); }

        /** Atomically updates the Value associated with an atom. */
        public void updateValue(Atom atom, Function<Value, Value> updater) {
            Optional.ofNullable(atomsById.get(atom.id())) // Operate on canonical atom
                .map(atoms::get)
                .ifPresent(valueRef -> {
                    long now = timeSource.get();
                    valueRef.updateAndGet(current -> {
                        var updated = updater.apply(current).updateTime(now);
                        var confDiff = updated.truth.confidence() - current.truth.confidence();
                        var boost = (confDiff > TRUTH_REVISION_CONFIDENCE_THRESHOLD) ? (float)(PRI_BOOST_ON_REVISION_MAX * confDiff) : 0;
                        return boost > 0 ? updated.boost(boost, now) : updated;
                    });
                });
        }

        public void updateTruth(Atom atom, Truth newTruth) { updateValue(atom, v -> v.withTruth(v.truth.merge(newTruth))); }
        public void boost(Atom atom, float amount) { updateValue(atom, v -> v.boost(amount, timeSource.get())); }

        public Atom sym(String name) { return add(Sym.of(name)); }
        public Var var(String name) { return add(new Var(name)); }
        public Expr expr(List<Atom> children) { return add(new Expr(children)); }
        public Expr expr(Atom... children) { return add(new Expr(List.of(children))); }
        public <T> Is<T> is(T value) { return add(new Is<>(value)); }
        public <T> Is<T> is(String id, T value) { return add(new Is<>(id, value)); }
        public Is<Function<List<Atom>, Optional<Atom>>> isFn(String name, Function<List<Atom>, Optional<Atom>> fn) {
            return add(new Is<>(name, fn));
        }

        /** Performs pattern matching query against atoms in memory. */
        public List<Answer> query(Atom pattern) {
            var queryPattern = add(pattern); // Ensure pattern is canonical & boost it slightly
            boost(queryPattern, PRI_ACCESS_BOOST * 0.2f);
            var unification = new Unify(this);
            var checkCount = new AtomicLong(0); var maxChecks = Math.min(5000 + size() / 10, 15000);

            return getCandidateStream(queryPattern)
                .limit(maxChecks * 5).distinct()
                .filter(candidate -> valueOrDefault(candidate).truth.confidence() >= TRUTH_MIN_CONFIDENCE_MATCH)
                .map(candidate -> {
                    if (checkCount.incrementAndGet() > maxChecks) return null; // Performance limit
                    return unification.unify(queryPattern, candidate, Bind.EMPTY).map(bind -> {
                        boost(candidate, PRI_ACCESS_BOOST); // Boost matched atom
                        return new Answer(candidate, bind);
                    }).orElse(null);
                })
                .filter(Objects::nonNull)
                .limit(INTERPRETER_MAX_RESULTS * 2)
                .sorted(Comparator.comparingDouble((Answer ans) -> valueOrDefault(ans.resultAtom()).getWeightedTruth()).reversed())
                .limit(INTERPRETER_MAX_RESULTS)
                .toList();
        }

        // Selects potential matching candidates based on pattern structure
        private Stream<Atom> getCandidateStream(Atom queryPattern) {
            return switch (queryPattern) {
                case Expr pExpr when pExpr.head() != null && pExpr.head().type() != AtomType.VAR ->
                    Stream.concat(
                        indexByHead.getOrDefault(pExpr.head(), Set.of()).stream().map(this::atom).flatMap(Optional::stream),
                        value(queryPattern).isPresent() ? Stream.of(queryPattern) : Stream.empty()
                    );
                case Var v -> atoms.keySet().stream(); // Full scan for variable pattern
                default -> { // Sym, Is, or Expr with Var head
                    Stream<Atom> direct = value(queryPattern).isPresent() ? Stream.of(queryPattern) : Stream.empty();
                    Stream<Atom> broad = (queryPattern instanceof Expr pExpr && pExpr.head() != null && pExpr.head().type() == AtomType.VAR) ?
                         atoms.keySet().stream().filter(a -> a.type() == AtomType.EXPR) : Stream.empty();
                    yield Stream.concat(direct, broad);
                }
            };
        }

        /** Decays Pri and removes low-Pri atoms ("forgetting"). Triggered by MaintenanceAgent. Returns number of atoms removed. */
        int decayAndForget() {
            if (!maintenanceRunning.compareAndSet(false, true)) return 0; // Ensure single execution

            try {
                final long now = timeSource.get();
                var initialSize = atoms.size();
                if (initialSize == 0) return 0;

                final var targetSize = capacity * FORGETTING_TARGET_MEM_SIZE_FACTOR / 100;
                List<Atom> victims = new ArrayList<>(initialSize / 10);
                int decayCount = 0;

                // Decay all and identify potential victims
                for (var entry : atoms.entrySet()) {
                    var atom = entry.getKey(); var valueRef = entry.getValue();
                    var decayedValue = valueRef.updateAndGet(v -> v.decay(now));
                    decayCount++;
                    var isProtected = atom instanceof Var || (atom instanceof Sym s && PROTECTED_SYMBOLS.contains(s.name));
                    if (!isProtected && decayedValue.getCurrentPri(now) < PRI_MIN_FORGET_THRESHOLD) {
                        victims.add(atom);
                    }
                }

                var removedCount = 0;
                var needsTrimming = initialSize > capacity;

                if (!victims.isEmpty() && needsTrimming) {
                    victims.sort(Comparator.comparingDouble(a -> valueOrDefault(a).getCurrentPri(now)));
                    var removalTargetCount = Math.max(0, initialSize - targetSize);
                    for (int i = 0; i < victims.size() && removedCount < removalTargetCount; i++) {
                        if (valueOrDefault(victims.get(i)).getCurrentPri(now) < PRI_MIN_FORGET_THRESHOLD) {
                            if (removeAtomInternal(victims.get(i))) removedCount++;
                        }
                    }
                }
                // Only log if significant changes or high memory pressure occurred
                if (removedCount > 0 || (needsTrimming && initialSize > 10)) {
                     System.out.printf("Memory Maintenance: Decayed %d atoms. Removed %d low-pri atoms (Threshold=%.3f). Size %d -> %d (Capacity=%d).%n",
                           decayCount, removedCount, PRI_MIN_FORGET_THRESHOLD, initialSize, atoms.size(), capacity);
                }
                return removedCount;

            } finally {
                maintenanceRunning.set(false);
            }
        }

        // Internal removal logic used by decayAndForget and remove-atom Is function
        boolean removeAtomInternal(Atom atom) {
            if (atoms.remove(atom) != null) {
                atomsById.remove(atom.id());
                removeIndices(atom);
                return true;
            }
            return false;
        }

        private void updateIndices(Atom atom) {
            if (atom instanceof Expr e && e.head() != null && e.head().type() != AtomType.VAR) {
                indexByHead.computeIfAbsent(e.head(), h -> new ConcurrentSkipListSet<>()).add(atom.id());
            }
        }
        private void removeIndices(Atom atom) {
            if (atom instanceof Expr e && e.head() != null && e.head().type() != AtomType.VAR) {
                indexByHead.computeIfPresent(e.head(), (k, v) -> v.remove(atom.id()) ? (v.isEmpty() ? null : v) : v);
            }
        }

        public int size() { return atoms.size(); }
        public int getCapacity() { return capacity; }
        public Stream<Atom> streamAtoms() { return atoms.keySet().stream(); } // For Is functions like get-atoms
    }

    /** Immutable record representing unification bindings (Variable -> Atom). */
    public record Bind(Map<Var, Atom> map) {
        public static final Bind EMPTY = new Bind(emptyMap());
        public Bind { map = Map.copyOf(map); } // Ensure immutability
        public boolean isEmpty() { return map.isEmpty(); }
        public Optional<Atom> get(Var var) { return Optional.ofNullable(map.get(var)); }
        /** Recursively resolves variable binding, handles cycles. */
        public Optional<Atom> getRecursive(Var var) {
            var current = map.get(var); if (current == null) return empty();
            Set<Var> visited = new HashSet<>(4); visited.add(var); // Small initial size
            while (current instanceof Var v) {
                if (!visited.add(v)) return empty(); // Cycle
                var next = map.get(v);
                if (next == null) return Optional.of(v); // Chain ends in unbound var
                current = next;
            }
            return Optional.of(current);
        }
        @Override public String toString() { return map.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", ", "{", "}")); }
    }

    /** Immutable record representing a query answer (matched atom and bindings). */
    public record Answer(Atom resultAtom, Bind bind) {}

    /** Performs unification between two atoms, producing bindings if successful. */
    public static class Unify {
        private final Mem space; // Needed for expr construction during substitution

        public Unify(Mem space) { this.space = space; }

        /** Unifies pattern and instance, returning bindings or empty Optional. */
        public Optional<Bind> unify(Atom pattern, Atom instance, Bind initialBind) {
            Deque<Pair<Atom, Atom>> stack = new ArrayDeque<>(8); // Small initial size
            stack.push(new Pair<>(pattern, instance));
            var currentBinds = initialBind;

            while (!stack.isEmpty()) {
                var task = stack.pop();
                var p = subst(task.a, currentBinds); var i = subst(task.b, currentBinds);

                if (p.equals(i)) continue; // Match

                switch (p) {
                    case Var pVar -> { // Pattern is Var
                        if (containsVar(i, pVar)) return empty(); // Occurs check fail
                        var updatedBinds = mergeBind(currentBinds, pVar, i);
                        if (updatedBinds == null) return empty(); // Conflicting bind
                        currentBinds = updatedBinds;
                    }
                    case Atom _ when i instanceof Var iVar -> { // Instance is Var
                        if (containsVar(p, iVar)) return empty(); // Occurs check fail
                        var updatedBinds = mergeBind(currentBinds, iVar, p);
                        if (updatedBinds == null) return empty(); // Conflicting bind
                        currentBinds = updatedBinds;
                    }
                    case Expr pExpr when i instanceof Expr iExpr -> { // Both Expr
                        var pChildren = pExpr.children; var iChildren = iExpr.children;
                        if (pChildren.size() != iChildren.size()) return empty(); // Arity mismatch
                        for (var j = pChildren.size() - 1; j >= 0; j--) { // Push children pairs (reverse order)
                            stack.push(new Pair<>(pChildren.get(j), iChildren.get(j)));
                        }
                    }
                    default -> { return empty(); } // Mismatch (Sym vs Expr, different Syms/Is)
                }
            }
            return Optional.of(currentBinds); // Success
        }

        /** Applies bindings recursively to an atom. Returns canonical atom from space. */
        public Atom subst(Atom atom, Bind bind) {
            if (bind.isEmpty() || atom.type() == AtomType.SYM || atom.type() == AtomType.IS) return atom; // Quick exit for constants

            return switch (atom) {
                case Var var -> bind.getRecursive(var).map(val -> val == var ? var : subst(val, bind)).orElse(var);
                case Expr expr -> {
                    var changed = false; List<Atom> newChildren = new ArrayList<>(expr.children.size());
                    for (var child : expr.children) {
                        var substChild = subst(child, bind);
                        if (child != substChild) changed = true;
                        newChildren.add(substChild);
                    }
                    yield changed ? space.expr(newChildren) : expr; // Use space.expr for canonicalization
                }
                default -> atom; // Should not happen due to initial check, but satisfies compiler
            };
        }

        // Checks if 'var' occurs within 'expr' (prevents infinite recursion). Stack-based to avoid deep recursion.
        private boolean containsVar(Atom expr, Var var) {
            Deque<Atom> stack = new ArrayDeque<>(); stack.push(expr);
            Set<Atom> visited = new HashSet<>(); // Avoid re-processing same sub-expression

            while (!stack.isEmpty()) {
                var current = stack.pop();
                if (!visited.add(current)) continue;
                switch (current) {
                    case Var v -> { if (v.equals(var)) return true; }
                    case Expr e -> e.children.forEach(stack::push); // Push children to check
                    default -> {} // Sym, Is cannot contain Vars
                }
            }
            return false;
        }

        // Merges a new binding (var=value) into existing bindings. Handles conflicts via recursive unification. Returns null on conflict.
        private @Nullable Bind mergeBind(Bind current, Var var, Atom value) {
            return current.getRecursive(var).map(existingValue -> // Var already bound?
                existingValue.equals(value) ? current : // Redundant bind
                unify(existingValue, value, current).orElse(null) // Conflict: Unify existing and new values
            ).orElseGet(() -> { // New binding
                var m = new HashMap<>(current.map()); m.put(var, value); return new Bind(m);
            });
        }
    }

    /** Parses MeTTa text into Atoms. */
    private static class MettaParser {
        // ... [Omitted: MettaParser internals - unchanged from v6.1] ...
        // Methods: parseSymbolOrIs, unescapeString, tokenize, parseAtomFromTokens, parseExprFromTokens, parse, parseAll
        // Inner Types: TokenType, Token, PeekableIterator
        // Exception: MettaParseException
        private final Cog cog;
        MettaParser(Cog cog) { this.cog = cog; }

        private Atom parseSymbolOrIs(String text) {
            return switch (text) {
                case "True" -> SYM_TRUE; case "False" -> SYM_FALSE; case "Nil" -> SYM_NIL;
                default -> { try { yield cog.IS(Double.parseDouble(text)); } catch (NumberFormatException e) { yield cog.S(text); } }
            };
        }
        private String unescapeString(String s) { return s.replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\"); }

        private List<Token> tokenize(String text) {
            List<Token> tokens = new ArrayList<>(); var line = 1; var col = 1; var i = 0; final var len = text.length();
            while (i < len) {
                var c = text.charAt(i); var startCol = col;
                if (Character.isWhitespace(c)) { if (c == '\n') { line++; col = 1; } else col++; i++; }
                else if (c == '(') { tokens.add(new Token(TokenType.LPAREN, "(", line, startCol)); i++; col++; }
                else if (c == ')') { tokens.add(new Token(TokenType.RPAREN, ")", line, startCol)); i++; col++; }
                else if (c == ';') { while (i < len && text.charAt(i) != '\n') i++; } // Comment
                else if (c == '"') { // String literal
                    var start = i; i++; col++; var escaped = false;
                    while (i < len) { var nc = text.charAt(i); if (nc == '"' && !escaped) { i++; col++; tokens.add(new Token(TokenType.STRING, text.substring(start, i), line, startCol)); break; } if (nc == '\n') throw new MettaParseException("Unterminated string at line " + line + ":" + startCol); escaped = (nc == '\\' && !escaped); i++; col++; }
                    if (i == len && (tokens.isEmpty() || tokens.getLast().type != TokenType.STRING)) throw new MettaParseException("Unterminated string at EOF");
                } else if (c == VAR_PREFIX.charAt(0)) { // Variable
                    var start = i; i++; col++; while (i < len && !Character.isWhitespace(text.charAt(i)) && !"();".contains(String.valueOf(text.charAt(i)))) { i++; col++; } var varName = text.substring(start, i); if (varName.length() <= VAR_PREFIX.length()) throw new MettaParseException("Invalid var name '" + varName + "' at line " + line + ":" + startCol); tokens.add(new Token(TokenType.VAR, varName, line, startCol));
                } else { // Symbol or Number
                    var start = i; while (i < len && !Character.isWhitespace(text.charAt(i)) && !"();".contains(String.valueOf(text.charAt(i)))) { i++; col++; } tokens.add(new Token(TokenType.SYMBOL, text.substring(start, i), line, startCol));
                }
            }
            return tokens;
        }
        private Atom parseAtomFromTokens(PeekableIterator<Token> it) {
            if (!it.hasNext()) throw new MettaParseException("Unexpected EOF"); var token = it.next();
            return switch (token.type) {
                case LPAREN -> parseExprFromTokens(it);
                case VAR -> cog.V(token.text.substring(VAR_PREFIX.length()));
                case SYMBOL -> parseSymbolOrIs(token.text);
                case STRING -> cog.IS(unescapeString(token.text.substring(1, token.text.length() - 1)));
                case RPAREN -> throw new MettaParseException("Unexpected ')' at line " + token.line + ":" + token.col);
                case EOF -> throw new MettaParseException("Internal Error: Reached EOF token");
            };
        }
        private Expr parseExprFromTokens(PeekableIterator<Token> it) {
            List<Atom> children = new ArrayList<>();
            while (true) {
                if (!it.hasNext()) throw new MettaParseException("Unterminated expression");
                var nextToken = it.peek();
                if (nextToken.type == TokenType.RPAREN) { it.next(); return cog.E(children); }
                children.add(parseAtomFromTokens(it));
            }
        }
        public Atom parse(String text) {
             var tokens = tokenize(text); if (tokens.isEmpty()) throw new MettaParseException("Empty input");
             var it = new PeekableIterator<>(tokens.iterator()); var result = parseAtomFromTokens(it);
             if (it.hasNext()) throw new MettaParseException("Extra token(s) found: " + it.peek()); return result;
        }
        public List<Atom> parseAll(String text) {
            var tokens = tokenize(text); List<Atom> results = new ArrayList<>();
            var it = new PeekableIterator<>(tokens.iterator()); while (it.hasNext()) { results.add(parseAtomFromTokens(it)); } return results;
        }
        private enum TokenType { LPAREN, RPAREN, SYMBOL, VAR, STRING, EOF }
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

    /** General purpose immutable pair record. */
    private record Pair<A, B>(A a, B b) {}

    /** Initializes core symbols, Is functions, and loads bootstrap MeTTa rules. */
    private static class Core {
        // Core MeTTa rules including basic types, operators, and default agent logic.
        // Agent logic now expects agent ID as the first argument for context.
        private static final String CORE_METTA_RULES = """
            ; --- Basic Types & Syntax ---
            (: = Type) (: : Type) (: -> Type) (: Type Type)
            (: True Type) (: False Type) (: Nil Type) (: Number Type) (: String Type) (: JavaObject Type)
            (: Bool Type) (: Atom Type) (: List Type) (: Fn Type)
            (: State Type) (: Action Type) (: Goal Type) (: Utility Type) (: Implies Type) (: Seq Type) (: Act Type)
            (: Agent Type) ; Type for Agent Symbols

            ; Type Assertions for constants
            (: True Bool) (: False Bool) (: Nil List)

            ; Type assertions for core Is functions
            (: match Fn) (: eval Fn) (: add-atom Fn) (: remove-atom Fn) (: get-value Fn) (: &self Fn) (: get-atoms Fn) (: get-agent-game Fn)
            (: _+ Fn) (: _- Fn) (: _* Fn) (: _/ Fn) (: _== Fn) (: _> Fn) (: _< Fn)
            (: Concat Fn) (: If Fn) (: Let Fn) (: IsEmpty Fn) (: IsNil Fn) (: RandomFloat Fn)
            (: RandomChoice Fn) (: FirstNumOrZero Fn) (: First Fn)
            (: AgentPerceive Fn) (: AgentAvailableActions Fn) (: AgentExecute Fn) (: AgentLearn Fn) (: CheckGoal Fn) (: UpdateUtility Fn)
            (: JavaNew Fn) (: JavaCall Fn) (: JavaStaticCall Fn) (: JavaField Fn) (: JavaProxy Fn)
            (: PerformMaintenance Fn) ; System Agent Example

            ; --- Basic Operations & Logic ---
            (= (+ $a $b) (_+ $a $b)) (= (- $a $b) (_- $a $b)) (= (* $a $b) (_* $a $b)) (= (/ $a $b) (_/ $a $b))
            (= (== $a $b) (_== $a $b)) (= (> $a $b) (_> $a $b)) (= (< $a $b) (_< $a $b))

            (= (FirstNumOrZero ($num . $rest)) $num) :- (: $num Number)
            (= (FirstNumOrZero ($other . $rest)) (FirstNumOrZero $rest))
            (= (FirstNumOrZero $num) $num) :- (: $num Number)
            (= (FirstNumOrZero Nil) 0.0) (= (FirstNumOrZero $any) 0.0)

            (= (First ($a . $b)) $a) (= (First Nil) Nil) (= (First $x) $x) ; Basic First

            ; Peano Arithmetic Example (Illustrative)
            (: Z Number) (: S Fn) (: add Fn)
            (= (add Z $n) $n) (= (add (S $m) $n) (S (add $m $n)))

            ; --- Default Agent Logic (Agent ID is the first argument) ---
            (= (AgentStep $agent)
               (AgentLearn $agent (AgentExecute $agent (AgentSelectAction $agent (AgentPerceive $agent)))))

            ; Learning: Update utility based on reward from Act result (expects (Act Is<List<Percepts>> Is<Reward>))
            (= (AgentLearn $agent (Act $perceptsIs $rewardIs))
               (UpdateUtility $agent $rewardIs)) ; Simplified: doesn't use state/action yet

            ; Utility update: Adds reward to a (Utility <agent> $val) atom
            (= (UpdateUtility $agent $rewardIs)
               (Let (= $utilAtom (= (Utility $agent) $val))
                 (Let (= $currentUtilVal (FirstNumOrZero (match &self $utilAtom $val)))
                   (Let (= $rewardVal (FirstNumOrZero (eval $rewardIs)))
                     (Let (= $newUtil (+ $currentUtilVal $rewardVal))
                       (add-atom (= (Utility $agent) $newUtil)))))))

            ; Action selection: Choose randomly from available actions based on current state (first percept)
            (= (AgentSelectAction $agent $perceptsIs)
               (Let (= $state (First (eval $perceptsIs))) ; Evaluate Is<List> and get first percept
                 (Let (= $actionsIs (AgentAvailableActions $agent $state))
                   (RandomChoice $actionsIs))))

            ; Goal Check: Check if agent's goal pattern exists in memory
            (= (CheckGoal $agent $goalPattern)
               (If (IsEmpty (match &self $goalPattern $goalPattern)) False True))

            ; --- System Maintenance Agent Logic ---
            (= (AgentStep MaintenanceAgent)
               (PerformMaintenance)) ; Simple: just execute the maintenance action

            ; Utility for maintenance agent could track memory health or removal count
            (= (UpdateUtility MaintenanceAgent $removalCountIs)
               (Let (= $currentCount (FirstNumOrZero (match &self (= (MaintenanceRemovedCount) $c) $c)))
                 (Let (= $newCount (FirstNumOrZero (eval $removalCountIs)))
                   (add-atom (= (MaintenanceRemovedCount) (+ $currentCount $newCount))))))

            ; --- End Core Rules ---
            """;
        private final Cog cog;

        Core(Cog cog) {
            this.cog = cog;
            // Assign canonical symbols
            SYM_EQ = cog.S("="); SYM_COLON = cog.S(":"); SYM_ARROW = cog.S("->"); SYM_TYPE = cog.S("Type");
            SYM_TRUE = cog.S("True"); SYM_FALSE = cog.S("False"); SYM_SELF = cog.S("Self"); SYM_NIL = cog.S("Nil");
            SYM_MAINT_AGENT = cog.S("MaintenanceAgent"); // Canonical symbol for system agent

            initIsFn(); // Define bridges to Java functionality
            cog.load(CORE_METTA_RULES); // Load core rules & agent logic

            // Ensure core symbols/types have high confidence/priority
            Stream.concat(PROTECTED_SYMBOLS.stream(), Stream.of("Z", "S", "add")) // Add Peano symbols
                  .map(cog::S).distinct() // Ensure unique symbols
                  .forEach(sym -> {
                      cog.mem.updateTruth(sym, Truth.TRUE);
                      cog.mem.boost(sym, 1); // Max boost for core elements
                  });
        }

        // --- Helpers for Is Functions ---
        private static Optional<Double> numValue(@Nullable Atom atom, Cog cog) {
            Function<Atom, Optional<Double>> extractor = a -> (a instanceof Is<?> is && is.value instanceof Number n) ? Optional.of(n.doubleValue()) : empty();
            return Optional.ofNullable(atom).flatMap(a -> extractor.apply(a).or(() -> cog.evalBest(a).flatMap(extractor)));
        }
        @SuppressWarnings("unchecked") static Optional<List<Atom>> listValue(@Nullable Atom atom) {
            return (atom instanceof Is<?> is && is.value instanceof List<?> l && l.stream().allMatch(i -> i instanceof Atom)) ? Optional.of((List<Atom>) is.value) : empty();
        }
        private static Optional<String> stringValue(@Nullable Atom atom) { return (atom instanceof Is<?> is && is.value instanceof String s) ? Optional.of(s) : (atom instanceof Sym sy ? Optional.of(sy.name) : empty()); }
        private static Optional<Atom> applyNumOp(List<Atom> args, Cog cog, BinaryOperator<Double> op) {
            return (args.size() != 2) ? empty() : numValue(args.get(0), cog).flatMap(n1 -> numValue(args.get(1), cog).map(n2 -> cog.IS(op.apply(n1, n2))));
        }
        private static Optional<Atom> applyNumPred(List<Atom> args, Cog cog, BiPredicate<Double, Double> op) {
            return (args.size() != 2) ? empty() : numValue(args.get(0), cog).flatMap(n1 -> numValue(args.get(1), cog).map(n2 -> op.test(n1, n2) ? SYM_TRUE : SYM_FALSE));
        }

        /** Defines the `Is` Fn atoms that bridge MeTTa symbols to Java code execution. */
        private void initIsFn() {
            // Core Ops
            cog.IS("match", args -> (args.size() != 3) ? empty() : Optional.ofNullable((args.getFirst() instanceof Is<?> g && g.value instanceof Mem ts) ? ts : cog.mem).map(space -> cog.IS(space.query(args.get(1)).stream().map(ans -> cog.interp.subst(args.get(2), ans.bind())).toList())));
            cog.IS("eval", args -> args.isEmpty() ? empty() : cog.evalBest(args.getFirst()));
            cog.IS("add-atom", args -> args.isEmpty() ? empty() : Optional.of(cog.add(args.getFirst())));
            cog.IS("remove-atom", args -> Optional.of((!args.isEmpty() && cog.mem.removeAtomInternal(args.getFirst())) ? SYM_TRUE : SYM_FALSE));
            cog.IS("get-value", args -> args.isEmpty() ? empty() : cog.mem.value(args.getFirst()).map(cog::IS));
            cog.IS("&self", args -> Optional.of(cog.IS(cog.mem))); // Reference to current memory space
            cog.IS("get-atoms", args -> Optional.of(cog.IS(cog.mem.streamAtoms().toList()))); // Get all atoms as Is<List<Atom>>

            // Arithmetic / Comparison / String / Control / Util (Unchanged)
            cog.IS("_+", args -> applyNumOp(args, cog, Double::sum)); cog.IS("_-", args -> applyNumOp(args, cog, (a, b) -> a - b));
            cog.IS("_*", args -> applyNumOp(args, cog, (a, b) -> a * b)); cog.IS("_/", args -> applyNumOp(args, cog, (a, b) -> Math.abs(b) < 1e-12 ? Double.NaN : a / b));
            cog.IS("_==", args -> applyNumPred(args, cog, (a, b) -> Math.abs(a - b) < 1e-9)); cog.IS("_>", args -> applyNumPred(args, cog, (a, b) -> a > b)); cog.IS("_<", args -> applyNumPred(args, cog, (a, b) -> a < b));
            cog.IS("Concat", args -> Optional.of(cog.IS(args.stream().map(Core::stringValue).flatMap(Optional::stream).collect(Collectors.joining()))));
            cog.IS("If", args -> (args.size() != 3) ? empty() : cog.evalBest(args.getFirst()).map(cond -> cond.equals(SYM_TRUE) ? args.get(1) : args.get(2)));
            cog.IS("Let", args -> (args.size() != 2 || !(args.getFirst() instanceof Expr b) || !b.head().equals(SYM_EQ) || b.children.size() != 3 || !(b.children.get(1) instanceof Var v)) ? empty() : cog.evalBest(b.children.get(2)).map(val -> cog.unify.subst(args.get(1), new Bind(Map.of(v, val)))));
            cog.IS("IsEmpty", args -> args.isEmpty() ? Optional.of(SYM_TRUE) : cog.evalBest(args.getFirst()).map(res -> (res.equals(SYM_NIL) || (res instanceof Is<?> is && is.value instanceof List<?> l && l.isEmpty())) ? SYM_TRUE : SYM_FALSE));
            cog.IS("IsNil", args -> args.isEmpty() ? Optional.of(SYM_TRUE) : cog.evalBest(args.getFirst()).map(res -> res.equals(SYM_NIL) ? SYM_TRUE : SYM_FALSE));
            cog.IS("RandomFloat", args -> Optional.of(cog.IS(RandomGenerator.getDefault().nextDouble())));
            cog.IS("RandomChoice", args -> args.stream().findFirst().flatMap(Core::listValue).or(()-> args.stream().findFirst().flatMap(cog::evalBest).flatMap(Core::listValue)).filter(list -> !list.isEmpty()).map(list -> list.get(RandomGenerator.getDefault().nextInt(list.size()))).or(() -> Optional.of(SYM_NIL)));

            // --- Agent Support (Require Agent ID as first arg) ---
            BiFunction<List<Atom>, Function<Game, List<Atom>>, Optional<Atom>> agentGameListOp =
                (args, gameFn) -> (args.isEmpty() || !(args.getFirst() instanceof Sym agentId)) ? empty() :
                cog.getGameForAgent(agentId).map(game -> cog.IS(gameFn.apply(game)));

            BiFunction<List<Atom>, BiFunction<Game, Atom, List<Atom>>, Optional<Atom>> agentGameListOpWithState =
                (args, gameFn) -> (args.size() < 2 || !(args.getFirst() instanceof Sym agentId)) ? empty() :
                cog.getGameForAgent(agentId).flatMap(game ->
                    cog.evalBest(args.get(1)).map(stateAtom -> // Evaluate state argument
                        cog.IS(gameFn.apply(game, stateAtom)))); // Pass evaluated state

            BiFunction<List<Atom>, BiFunction<Game, Atom, Act>, Optional<Atom>> agentGameActOp =
                (args, gameFn) -> (args.size() < 2 || !(args.getFirst() instanceof Sym agentId)) ? empty() :
                cog.getGameForAgent(agentId).flatMap(game ->
                    cog.evalBest(args.get(1)).map(actionAtom -> { // Evaluate action argument
                        var actResult = gameFn.apply(game, actionAtom);
                        // Return structured result: (Act Is<List<Atom>> Is<Double>)
                        return cog.E(cog.S("Act"), cog.IS(actResult.newPercepts()), cog.IS(actResult.reward()));
                    }));

            cog.IS("AgentPerceive", args -> agentGameListOp.apply(args, game -> game.perceive(cog)));
            cog.IS("AgentAvailableActions", args -> agentGameListOpWithState.apply(args, (game, state) -> game.actions(cog, state)));
            cog.IS("AgentExecute", args -> agentGameActOp.apply(args, (game, action) -> game.exe(cog, action)));
            cog.IS("get-agent-game", args -> (args.isEmpty() || !(args.getFirst() instanceof Sym agentId)) ? empty() : cog.getGameForAgent(agentId).map(cog::IS));

            // --- System Agent Support ---
            cog.IS("PerformMaintenance", args -> {
                // Find the MaintenanceGame (assumes it's associated with SYM_MAINT_AGENT)
                return cog.getGameForAgent(SYM_MAINT_AGENT).flatMap(game -> {
                    if (game instanceof MaintenanceGame mg) {
                        var removed = mg.performMaintenanceStep();
                        // Return the count of removed atoms, could be used by UpdateUtility rule
                        return Optional.of(cog.IS((double)removed));
                    } else return empty();
                });
            });

            // JVM functions are registered by the Jvm class constructor
        }
    }

    /** Interface for the external game/environment an Agent interacts with. */
    public interface Game {
        /** Unique identifier or name for this game instance. */
        String gameId();
        /** Get current perceptions as Atoms for the given agent context. */
        List<Atom> perceive(Cog cog);
        /** Get available actions (as Atoms) from a given state Atom. */
        List<Atom> actions(Cog cog, Atom currentState);
        /** Execute an action Atom, return outcome (new percepts, reward). */
        Act exe(Cog cog, Atom action);
        /** Check if the game simulation should continue running. */
        boolean isRunning();
        /** Get the symbolic representation of the goal state for this game (helper). */
        String goalStateName(); // Added helper
    }

    /** Result of an agent's action execution in the game. */
    public record Act(List<Atom> newPercepts, double reward) {}

    /** Example minimal Game implementation. */
    static class SimpleGame implements Game {
        private final String id;
        private final Atom posA, posB, posGoal, moveAtoB, moveBtoGoal, moveOther, statePred;
        private final Cog cog;
        private Atom currentStateSymbol; // Internal state

        SimpleGame(Cog c, String id) {
            this.cog = c; this.id = id;
            // Define atoms specific to this game instance (or could be shared)
            posA = c.S("Pos_A_" + id); posB = c.S("Pos_B_" + id); posGoal = c.S("AtGoal_" + id);
            moveAtoB = c.S("Move_A_B_" + id); moveBtoGoal = c.S("Move_B_Goal_" + id); moveOther = c.S("Move_Other_" + id);
            statePred = c.S("State"); // Shared state predicate symbol
            currentStateSymbol = posA; // Start at A
        }

        @Override public String gameId() { return id; }
        @Override public String goalStateName() { return posGoal.id(); } // Return the ID string

        // Perception is the agent's current state: e.g., (State Agent_Sim1 Pos_A_Sim1)
        // NOTE: Requires agent ID to construct the state percept correctly. This info isn't directly available here.
        // The *agent* constructs its state representation based on perception. Here we just provide the location symbol.
        @Override public List<Atom> perceive(Cog cog) {
            // The agent's "AgentPerceive" rule should combine this with its ID (e.g., Self)
             return List.of(currentStateSymbol); // Return just the current location symbol
             // A more complete perception might be: cog.E(statePred, SYM_SELF /*Needs agent ID!*/, currentStateSymbol)
             // Let's assume the MeTTa rule handles creating the full state atom for now.
             // Or, the Is function can retrieve the agent ID and inject it. Let's refine `AgentPerceive` Is function.
             // **Decision:** Modify `AgentPerceive` Is function in Core.initFn to construct the state atom correctly.
             // Game.perceive should just return the core environmental state component.
        }

        @Override public List<Atom> actions(Cog cog, Atom currentStateAtom) {
            // Simplistic: Ignore currentStateAtom, use internal state. Real game parses stateAtom.
            if (currentStateSymbol.equals(posA)) return List.of(moveAtoB, moveOther);
            if (currentStateSymbol.equals(posB)) return List.of(moveBtoGoal, moveOther);
            return List.of(moveOther); // Only 'other' if at goal
        }

        @Override public Act exe(Cog cog, Atom actionSymbol) {
            var reward = -0.1; // Cost per step
            String logMsg = "executed " + actionSymbol.id();
            if (currentStateSymbol.equals(posA) && actionSymbol.equals(moveAtoB)) {
                currentStateSymbol = posB; reward = 0.1; logMsg += " -> B";
            } else if (currentStateSymbol.equals(posB) && actionSymbol.equals(moveBtoGoal)) {
                currentStateSymbol = posGoal; reward = 1.0; logMsg += " -> Goal!";
            } else if (actionSymbol.equals(moveOther)) {
                reward = -0.2; logMsg += " (Other)";
            } else {
                reward = -0.5; logMsg += " (Invalid Action!)"; // Penalty
            }
            // System.out.printf("Env (%s): %s%n", id, logMsg); // Reduced logging noise
            return new Act(perceive(cog), reward); // Return new state symbol and reward
        }

        @Override public boolean isRunning() { return !currentStateSymbol.equals(posGoal); }
    }

    /** System Game for Memory Maintenance. */
    static class MaintenanceGame implements Game {
        private final Cog cog;
        private final Atom actionPerformMaint;
        private final Atom statusOptimal, statusNeedsMaint;

        MaintenanceGame(Cog c) {
            this.cog = c;
            actionPerformMaint = c.S("PerformMaintenance");
            statusOptimal = c.S("MaintenanceStatus_Optimal");
            statusNeedsMaint = c.S("MaintenanceStatus_NeedsMaintenance");
        }
        @Override public String gameId() { return "SystemMaintenance"; }
        @Override public String goalStateName() { return statusOptimal.id(); } // Target state

        @Override public List<Atom> perceive(Cog cog) {
            // Perception is current memory status
            var currentSize = cog.mem.size();
            var capacity = cog.mem.getCapacity();
            var needsMaint = currentSize > capacity; // Simple trigger condition
            Atom status = needsMaint ? statusNeedsMaint : statusOptimal;
            // Return perception atoms: (MaintenanceStatus <StatusSymbol>) (MemSize <Current> <Capacity>)
            return List.of(
                cog.E(cog.S("MaintenanceStatus"), status),
                cog.E(cog.S("MemSize"), cog.IS((double)currentSize), cog.IS((double)capacity))
            );
        }

        @Override public List<Atom> actions(Cog cog, Atom currentState) {
            // Only action is to perform maintenance, perhaps only if needed
            // Parsing currentState would be better: (MaintenanceStatus NeedsMaintenance) -> PerformMaintenance
            var currentSize = cog.mem.size(); var capacity = cog.mem.getCapacity();
            return (currentSize > capacity) ? List.of(actionPerformMaint) : List.of(); // Only offer action if needed
        }

        @Override public Act exe(Cog cog, Atom action) {
            if (action.equals(actionPerformMaint)) {
                var removedCount = performMaintenanceStep();
                // Reward could be based on reduction, or just a small positive value for acting
                var reward = removedCount > 0 ? 0.1 * removedCount : (cog.mem.size() > cog.mem.getCapacity() ? -0.05 : 0.0);
                return new Act(perceive(cog), reward); // Return new status and reward
            }
            return new Act(perceive(cog), -0.1); // Penalty for unknown action
        }

        /** Performs one step of maintenance (called by Is function or internal logic). */
        public int performMaintenanceStep() {
            return cog.mem.decayAndForget(); // Delegate to Mem's method
        }

        @Override public boolean isRunning() { return true; } // Runs continuously
    }


    /** Bridges MeTTa execution to Java Reflection and MethodHandles. */
    private static class Jvm {
        // ... [Omitted: Jvm internals - largely unchanged from v6.1] ...
        // Includes: ALLOWED_CLASSES/PACKAGES, m2j, j2m, argType, findClass, compatibility checks, conversions
        // Methods: javaNew, invokeMethod (for call/static call), accessStaticField, findConstructor/Method, adjustArguments, javaCall, javaStaticCall, javaField, javaProxy, createInvocationHandler
        // Inner Exception: MettaProxyException
        private static final Set<String> ALLOWED_CLASSES = Set.of(
            "java.lang.String", "java.lang.Math", "java.util.ArrayList", "java.util.HashMap", "java.util.Random",
            "java.lang.Double", "java.lang.Integer", "java.lang.Boolean", "java.lang.Object", "java.lang.System",
            Cog.class.getName(), Cog.class.getName()+"$Atom", Cog.class.getName()+"$TestClass", // Allow demo class
            "java.lang.Runnable" );
        private static final Set<String> ALLOWED_PACKAGES = Set.of("java.lang", "java.util", "java.util.concurrent");
        private final Cog cog; private final MethodHandles.Lookup lookup;

        public Jvm(Cog c) {
            this.cog = c; this.lookup = MethodHandles.lookup();
            c.IS("JavaNew", this::javaNew); c.IS("JavaCall", this::javaCall);
            c.IS("JavaStaticCall", this::javaStaticCall); c.IS("JavaField", this::javaField);
            c.IS("JavaProxy", this::javaProxy);
        }

        private boolean isClassAllowed(String className) {
            if (ALLOWED_CLASSES.contains(className)) return true;
            return ALLOWED_PACKAGES.stream().anyMatch(pkg -> className.startsWith(pkg + "."));
        }
        private boolean isMemberAllowed(Class<?> clazz, String memberName) { return isClassAllowed(clazz.getName()); }

        @Nullable private Object m2j(@Nullable Atom x) { /* ... unchanged ... */
            return switch (x) {
                case Atom a when a.equals(SYM_NIL) -> null;
                case null -> null;
                case Is<?> is -> is.value; case Sym s when s.equals(SYM_TRUE) -> Boolean.TRUE; case Sym s when s.equals(SYM_FALSE) -> Boolean.FALSE; case Sym s -> s.name; case Var v -> v.id(); case Expr e -> e; };
        }
        private Atom j2m(@Nullable Object x) { /* ... unchanged ... */
             return switch (x) { case null -> SYM_NIL; case Atom a -> a; case Boolean b -> b ? SYM_TRUE : SYM_FALSE; case Number n -> cog.IS(n.doubleValue()); case List<?> l -> cog.IS(l.stream().map(this::j2m).toList()); case Object[] arr -> cog.IS(Arrays.stream(arr).map(this::j2m).toList()); default -> cog.IS(x); };
        }
        private Class<?> argType(@Nullable Atom atom) { /* ... unchanged ... */
            var obj = m2j(atom); return switch (obj) { case null -> Object.class; case Double v -> double.class; case Integer i -> int.class; case Long l -> long.class; case Float v -> float.class; case Boolean b -> boolean.class; case Character c -> char.class; case Byte b -> byte.class; case Short i -> short.class; default -> obj.getClass(); };
        }
        @Nullable private Class<?> findClass(String className) { /* ... unchanged ... */
            if (!isClassAllowed(className)) { System.err.println("Security: Class not allowed: " + className); return null; } try { return Class.forName(className); } catch (ClassNotFoundException e) { System.err.println("Error: Class not found: " + className); return null; }
        }
        private static boolean isTypeCompatible(Class<?> formal, Class<?> actual) { /* ... unchanged ... */
             if (formal.isAssignableFrom(actual)) return true; if (formal.isPrimitive()) return compatibleUnbox(formal, actual); if (actual.isPrimitive()) return compatibleBox(formal, actual); return false;
        }
        private static boolean compatibleUnbox(Class<?> f, Class<?> a) { return (f == int.class && a == Integer.class) || (f == double.class && a == Double.class) || (f == boolean.class && a == Boolean.class) || (f == long.class && a == Long.class) || (f == float.class && a == Float.class) || (f == char.class && a == Character.class) || (f == byte.class && a == Byte.class) || (f == short.class && a == Short.class); }
        private static boolean compatibleBox(Class<?> f, Class<?> a) { return compatibleUnbox(a, f); }

        @Nullable private Object attemptConversion(Object value, Class<?> t) { /* ... unchanged ... */
            if (value == null) return null; if (value instanceof Double d) { if (t == int.class || t == Integer.class) return d.intValue(); if (t == long.class || t == Long.class) return d.longValue(); if (t == float.class || t == Float.class) return d.floatValue(); if (t == short.class || t == Short.class) return d.shortValue(); if (t == byte.class || t == Byte.class) return d.byteValue(); } if (value instanceof String s) { try { if (t == double.class || t == Double.class) return Double.parseDouble(s); if (t == int.class || t == Integer.class) return Integer.parseInt(s); if (t == boolean.class || t == Boolean.class) return Boolean.parseBoolean(s); } catch (NumberFormatException ignored) {} } System.err.println("Warning: Could not convert " + value.getClass().getSimpleName() + " to " + t.getSimpleName()); return null;
        }

        public Optional<Atom> javaNew(List<Atom> args) { /* ... unchanged ... */
            if (args.isEmpty()) return Optional.of(cog.S("Error:JavaNew:NoClassName")); if (!(m2j(args.getFirst()) instanceof String className)) return Optional.of(cog.S("Error:JavaNew:ClassNameNotString")); var clazz = findClass(className); if (clazz == null) return Optional.of(cog.S("Error:JavaNew:ClassNotFound:" + className)); var cArgs = args.subList(1, args.size()); var jArgs = cArgs.stream().map(this::m2j).toArray(); var aTypes = cArgs.stream().map(this::argType).toArray(Class<?>[]::new); try { var constructor = findConstructor(clazz, aTypes); if (constructor == null) return Optional.of(cog.S("Error:JavaNew:ConstructorNotFound:" + Arrays.toString(aTypes))); if (!isMemberAllowed(clazz, "<init>")) return Optional.of(cog.S("Error:JavaNew:ConstructorNotAllowed")); var adjArgs = adjustArguments(jArgs, constructor.getParameterTypes()); var instance = constructor.newInstance(adjArgs); return Optional.of(j2m(instance)); } catch (Exception e) { System.err.println("Error: Constructor invocation failed for " + className + ": " + e); if (e.getCause() != null) e.getCause().printStackTrace(); return Optional.of(cog.S("Error:JavaNew:ConstructorFailed:" + e.getClass().getSimpleName())); }
        }

        private Optional<Atom> invokeMethod(boolean isStatic, List<Atom> args) { /* ... unchanged ... */
             var minArgs = isStatic ? 2 : 2; if (args.size() < minArgs) return Optional.of(cog.S("Error:Invoke:InsufficientArgs")); Object target = null; Class<?> clazz; String methodName; int mArgIdx; if (isStatic) { if (!(m2j(args.getFirst()) instanceof String cn)) return Optional.of(cog.S("Error:Invoke:ClassNameNotString")); clazz = findClass(cn); if (clazz == null) return Optional.of(cog.S("Error:Invoke:ClassNotFound:" + cn)); if (!(m2j(args.get(1)) instanceof String mn)) return Optional.of(cog.S("Error:Invoke:MethodNameNotString")); methodName = mn; mArgIdx = 2; } else { target = m2j(args.getFirst()); if (target == null) return Optional.of(cog.S("Error:Invoke:TargetInstanceNull")); clazz = target.getClass(); if (!isClassAllowed(clazz.getName())) return Optional.of(cog.S("Error:Invoke:ClassNotAllowed:" + clazz.getName())); if (!(m2j(args.get(1)) instanceof String mn)) return Optional.of(cog.S("Error:Invoke:MethodNameNotString")); methodName = mn; mArgIdx = 2; } if (!isMemberAllowed(clazz, methodName)) return Optional.of(cog.S("Error:Invoke:MemberNotAllowed:" + methodName)); var mArgs = args.subList(mArgIdx, args.size()); var jArgs = mArgs.stream().map(this::m2j).toArray(); var aTypes = mArgs.stream().map(this::argType).toArray(Class<?>[]::new); try { var m = findMethod(clazz, methodName, aTypes); if (m == null) return Optional.of(isStatic && mArgs.isEmpty() ? accessStaticField(clazz, methodName) : cog.S("Error:Invoke:MethodNotFound:" + methodName + Arrays.toString(aTypes))); var h = lookup.unreflect(m); var adjArgs = adjustArguments(jArgs, m.getParameterTypes()); return Optional.of(j2m((isStatic ? h : h.bindTo(target)).invokeWithArguments(adjArgs))); } catch (Throwable e) { System.err.println("Error: Method invocation failed for " + clazz.getName() + "." + methodName + ": " + e); if (e instanceof InvocationTargetException ite && ite.getCause() != null) ite.getCause().printStackTrace(); else e.printStackTrace(); return Optional.of(cog.S("Error:Invoke:InvocationFailed:" + e.getClass().getSimpleName())); }
        }
        private Atom accessStaticField(Class<?> clazz, String fieldName) { /* ... unchanged ... */
            try { var f = clazz.getField(fieldName); if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) return cog.S("Error:Invoke:FieldNotStatic:" + fieldName); if (!isMemberAllowed(clazz, fieldName)) return cog.S("Error:Invoke:MemberNotAllowed:" + fieldName); return j2m(f.get(null)); } catch (NoSuchFieldException e) { return cog.S("Error:Invoke:MethodOrFieldNotFound:" + fieldName); } catch (IllegalAccessException e) { return cog.S("Error:Invoke:FieldAccessDenied:" + fieldName); }
        }
        @Nullable private java.lang.reflect.Constructor<?> findConstructor(Class<?> clazz, Class<?>[] argTypes) { /* ... unchanged ... */
             try { return clazz.getConstructor(argTypes); } catch (NoSuchMethodException e) { for (var c : clazz.getConstructors()) { if (c.getParameterCount() == argTypes.length && parametersMatch(c.getParameterTypes(), argTypes)) return c; } return null; }
        }
        @Nullable private Method findMethod(Class<?> clazz, String methodName, Class<?>[] argTypes) { /* ... unchanged ... */
            try { return clazz.getMethod(methodName, argTypes); } catch (NoSuchMethodException e) { for (var m : clazz.getMethods()) { if (m.getName().equals(methodName) && m.getParameterCount() == argTypes.length && parametersMatch(m.getParameterTypes(), argTypes)) return m; } return null; }
        }
        private boolean parametersMatch(Class<?>[] formalTypes, Class<?>[] actualTypes) { /* ... unchanged ... */
            if (formalTypes.length != actualTypes.length) return false; for (var i = 0; i < formalTypes.length; i++) { if (!isTypeCompatible(formalTypes[i], actualTypes[i])) return false; } return true;
        }
        private Object[] adjustArguments(Object[] args, Class<?>[] formalTypes) { /* ... unchanged ... */
            if (args.length != formalTypes.length) throw new IllegalArgumentException("Arg count mismatch"); var adj = new Object[args.length]; for (var i = 0; i < args.length; i++) { if (args[i] != null && !isTypeCompatible(formalTypes[i], args[i].getClass())) { adj[i] = attemptConversion(args[i], formalTypes[i]); if (adj[i] == null && formalTypes[i].isPrimitive()) throw new IllegalArgumentException("Cannot assign null/incompatible type to primitive " + formalTypes[i].getSimpleName()); } else if (args[i] == null && formalTypes[i].isPrimitive()) { throw new IllegalArgumentException("Cannot assign null to primitive " + formalTypes[i].getSimpleName()); } else { adj[i] = args[i]; } } return adj;
        }

        public Optional<Atom> javaCall(List<Atom> args) { return invokeMethod(false, args); }
        public Optional<Atom> javaStaticCall(List<Atom> args) { return invokeMethod(true, args); }

        public Optional<Atom> javaField(List<Atom> args) { /* ... unchanged ... */
            if (args.size() < 2 || args.size() > 3) return Optional.of(cog.S("Error:JavaField:InvalidArgs")); var tAtom = args.get(0); var tOrC = m2j(tAtom); if (!(m2j(args.get(1)) instanceof String fName)) return Optional.of(cog.S("Error:JavaField:FieldNameNotString")); Class<?> clazz; Object instance = null; var isStatic = false; if (tOrC instanceof String cn) { clazz = findClass(cn); isStatic = true; } else if (tOrC != null) { clazz = tOrC.getClass(); instance = tOrC; } else { return Optional.of(cog.S("Error:JavaField:InvalidTarget")); } if (clazz == null) return Optional.of(cog.S("Error:JavaField:ClassNotFound")); if (!isMemberAllowed(clazz, fName)) return Optional.of(cog.S("Error:JavaField:FieldNotAllowed")); try { var field = clazz.getField(fName); if (isStatic != java.lang.reflect.Modifier.isStatic(field.getModifiers())) return Optional.of(cog.S("Error:JavaField:StaticMismatch")); if (args.size() == 2) { return Optional.of(j2m(field.get(instance))); } else { var vAtom = args.get(2); var vSet = m2j(vAtom); var adjVal = vSet; if (vSet != null && !isTypeCompatible(field.getType(), vSet.getClass())) { adjVal = attemptConversion(vSet, field.getType()); if (adjVal == null && field.getType().isPrimitive()) return Optional.of(cog.S("Error:JavaField:TypeMismatch:CannotSetPrimitiveNull")); if (adjVal == null && vSet != null) return Optional.of(cog.S("Error:JavaField:TypeMismatch:CannotConvert")); } else if (vSet == null && field.getType().isPrimitive()) { return Optional.of(cog.S("Error:JavaField:TypeMismatch:CannotSetPrimitiveNull")); } field.set(instance, adjVal); return Optional.of(SYM_TRUE); } } catch (NoSuchFieldException e) { return Optional.of(cog.S("Error:JavaField:NotFound:" + fName)); } catch (IllegalAccessException e) { return Optional.of(cog.S("Error:JavaField:AccessDenied:" + fName)); } catch (Exception e) { System.err.println("Error: Field op failed: " + e); return Optional.of(cog.S("Error:JavaField:OperationFailed:" + e.getClass().getSimpleName())); }
        }

        public Optional<Atom> javaProxy(List<Atom> args) { /* ... unchanged ... */
             if (args.size() != 2) return Optional.of(cog.S("Error:JavaProxy:InvalidArgs")); if (!(m2j(args.get(0)) instanceof String iName)) return Optional.of(cog.S("Error:JavaProxy:InterfaceNameNotString")); var hExpr = args.get(1); var iface = findClass(iName); if (iface == null || !iface.isInterface()) return Optional.of(cog.S("Error:JavaProxy:InvalidInterface:" + iName)); if (!isClassAllowed(iName)) return Optional.of(cog.S("Error:JavaProxy:InterfaceNotAllowed")); try { var pInst = Proxy.newProxyInstance(this.getClass().getClassLoader(), new Class<?>[]{iface}, createInvocationHandler(hExpr)); return Optional.of(j2m(pInst)); } catch (Exception e) { System.err.println("Error: Proxy creation failed: " + e); return Optional.of(cog.S("Error:JavaProxy:CreationFailed")); }
        }
        private InvocationHandler createInvocationHandler(Atom handlerExprTemplate) { /* ... unchanged ... */
             cog.add(handlerExprTemplate); return (proxy, method, methodArgs) -> { var pAtom = j2m(proxy); var mNameAtom = cog.S(method.getName()); List<Atom> aAtoms = (methodArgs == null) ? List.of() : Arrays.stream(methodArgs).map(this::j2m).toList(); var argsListAtom = cog.IS(aAtoms); var bindings = new Bind(Map.of(cog.V("proxy"), pAtom, cog.V("method"), mNameAtom, cog.V("args"), argsListAtom)); var exprToEval = cog.unify.subst(handlerExprTemplate, bindings); var mettaResultOpt = cog.evalBest(exprToEval); var javaResult = mettaResultOpt.map(this::m2j).orElse(null); var returnType = method.getReturnType(); if (returnType == void.class || returnType == Void.class) return null; if (javaResult == null) { if (returnType.isPrimitive()) throw new MettaProxyException("MeTTa handler returned null/Nil for primitive return type " + returnType.getName()); return null; } if (isTypeCompatible(returnType, javaResult.getClass())) return javaResult; var convertedResult = attemptConversion(javaResult, returnType); if (convertedResult != null) return convertedResult; throw new MettaProxyException("MeTTa handler result type (" + javaResult.getClass().getName() + ") incompatible with method return type (" + returnType.getName() + ")"); };
        }
        static class MettaProxyException extends RuntimeException { MettaProxyException(String message) { super(message); } }
    }


    /** Interprets MeTTa expressions by applying rewrite rules and executing Is functions. */
    public class Interp {
        private final Cog cog;
        public Interp(Cog cog) { this.cog = cog; }
        public Atom subst(Atom atom, Bind bind) { return cog.unify.subst(atom, bind); }

        /** Evaluates an atom, returning a list of possible results within maxDepth. */
        public List<Atom> eval(Atom atom, int maxDepth) {
            var results = evalRecursive(atom, maxDepth, new HashSet<>());
            // If evaluation only returns the original atom (due to depth limit, cycle, or no rules), return just that.
            // Otherwise, filter out the original atom if other results exist.
            var finalResults = results.stream().distinct().limit(INTERPRETER_MAX_RESULTS).toList();
            return (finalResults.size() == 1 && finalResults.getFirst().equals(atom)) || finalResults.isEmpty() ?
                   List.of(atom) : finalResults.stream().filter(r -> !r.equals(atom)).toList();
        }

        /** Recursive evaluation core with depth/cycle detection. */
        private List<Atom> evalRecursive(Atom atom, int depth, Set<String> visitedInPath) {
            // --- Termination Checks ---
            if (depth <= 0 || !visitedInPath.add(atom.id())) return List.of(atom); // Depth or cycle limit

            List<Atom> combinedResults = new ArrayList<>();
            try {
                switch (atom) {
                    // --- Base Cases ---
                    case Sym s -> combinedResults.add(s);
                    case Var v -> combinedResults.add(v);
                    case Is<?> is when !is.isFn() -> combinedResults.add(is);

                    // --- Evaluate Expressions ---
                    case Expr expr -> {
                        final boolean[] ruleOrFnApplied = {false};

                        // 2a: Specific equality rule `(= <expr> $result)`
                        var resultVarS = cog.V("evalResS" + depth); Atom specQuery = cog.E(SYM_EQ, expr, resultVarS);
                        var specMatches = cog.mem.query(specQuery);
                        if (!specMatches.isEmpty()) {
                            ruleOrFnApplied[0] = true;
                            specMatches.forEach(m -> m.bind.get(resultVarS).ifPresent(target ->
                                combinedResults.addAll(evalRecursive(target, depth - 1, new HashSet<>(visitedInPath)))));
                            if (combinedResults.size() >= INTERPRETER_MAX_RESULTS) break; // Limit results
                        }

                        // 2b: General equality rule `(= <pattern> <template>)`
                        if (!ruleOrFnApplied[0] || combinedResults.size() < 5) { // Allow general rules if specific fails or yields few results
                             var pVar = cog.V("p" + depth); var tVar = cog.V("t" + depth); Atom genQuery = cog.E(SYM_EQ, pVar, tVar);
                             cog.mem.query(genQuery).forEach(ruleMatch -> {
                                 ruleMatch.bind.get(pVar).ifPresent(pattern -> ruleMatch.bind.get(tVar).ifPresent(template -> {
                                     if (pattern.equals(expr)) return; // Skip self-match
                                     cog.unify.unify(pattern, expr, Bind.EMPTY).ifPresent(exprBind -> {
                                         var result = subst(template, exprBind);
                                         if (!result.equals(expr)) { // Avoid trivial loops
                                             combinedResults.addAll(evalRecursive(result, depth - 1, new HashSet<>(visitedInPath)));
                                         }
                                     });
                                 }));
                                 if (!combinedResults.isEmpty()) ruleOrFnApplied[0] = true; // Mark applied if unification succeeded
                                 if (combinedResults.size() >= INTERPRETER_MAX_RESULTS) return; // Break stream implicitly
                             });
                             if (combinedResults.size() >= INTERPRETER_MAX_RESULTS) break; // Limit results
                        }

                        // 2c: Is Function execution (Applicative Order)
                        if ((!ruleOrFnApplied[0] || combinedResults.isEmpty()) && expr.head() instanceof Is<?> isFn && isFn.isFn()) {
                            ruleOrFnApplied[0] = true; // Consider function application attempt as "applied"
                            List<Atom> evaluatedArgs = new ArrayList<>(); boolean argEvalOk = true;
                            for (var arg : expr.tail()) {
                                var argResults = evalRecursive(arg, depth - 1, new HashSet<>(visitedInPath));
                                // Strict: single result per arg. Meta-rules could change this.
                                if (argResults.size() != 1 || argResults.getFirst().id().startsWith("Error:")) { argEvalOk = false; break; }
                                evaluatedArgs.add(argResults.getFirst());
                            }
                            if (argEvalOk) {
                                isFn.apply(evaluatedArgs).ifPresent(execResult ->
                                    combinedResults.addAll(evalRecursive(execResult, depth - 1, new HashSet<>(visitedInPath)))
                                );
                            } // If arg eval fails, function isn't called, fallback might occur
                        }

                        // 2d: Fallback: Evaluate children and reconstruct (if no rules applied or results were empty)
                         if (combinedResults.isEmpty()) { // Only if nothing else yielded results
                            var childrenChanged = false; List<Atom> evaluatedChildren = new ArrayList<>();
                            var head = expr.head(); var tail = expr.tail();
                            // Eval Head
                            var newHead = head;
                            if (head != null) {
                                var headRes = evalRecursive(head, depth - 1, new HashSet<>(visitedInPath));
                                if (headRes.size() == 1) { newHead = headRes.getFirst(); childrenChanged |= !newHead.equals(head); }
                                // else: ambiguous or no change, keep original head
                            }
                            evaluatedChildren.add(newHead);
                            // Eval Tail
                            for (var child : tail) {
                                var childRes = evalRecursive(child, depth - 1, new HashSet<>(visitedInPath));
                                if (childRes.size() == 1) { evaluatedChildren.add(childRes.getFirst()); childrenChanged |= !childRes.getFirst().equals(child); }
                                else evaluatedChildren.add(child); // Keep original if ambiguous/no change
                            }
                            // Only add reconstructed if changed, else original expression (avoids loops)
                            combinedResults.add(childrenChanged ? cog.E(evaluatedChildren) : expr);
                        }
                    }

                    // --- Is functions evaluated directly return themselves ---
                    case Is<?> isFn -> combinedResults.add(isFn);
                }
            } finally {
                visitedInPath.remove(atom.id()); // Backtrack
            }
            // If results contain only the original atom, return just that. Otherwise, filter it out if other results exist.
            // This is now handled in the public eval method.
            return combinedResults;
        }
    }

    /** Context for a running agent instance. */
    private static class AgentContext {
        final Atom agentId;
        final Game game;
        final Agent agent;
        final Atom goalPattern;
        final int maxCycles; // -1 for indefinite
        final AtomicLong cyclesCompleted = new AtomicLong(0);
        final AtomicBoolean isGoalMet = new AtomicBoolean(false);
        final AtomicBoolean isRunning = new AtomicBoolean(true); // Can be set to false by scheduler
        final AtomicReference<Atom> lastStateAtom = new AtomicReference<>(); // Track last known state

        AgentContext(Cog cog, Agent agent, Game game, Atom goalPattern, int maxCycles) {
            this.agentId = agent.id;
            this.game = game;
            this.goalPattern = goalPattern;
            this.maxCycles = maxCycles;
            this.agent = agent; // Agent logic handler tied to ID
        }
    }

    /** Manages concurrent execution of multiple agents based on priority. */
    private class AgentScheduler {
        private final Cog cog;
        private final ConcurrentMap<Atom, AgentContext> activeAgents = new ConcurrentHashMap<>();
        private final ExecutorService agentExecutor =
            Executors.newSingleThreadScheduledExecutor();
//                Executors.newCachedThreadPool(r -> {
//                    var t = new Thread(r, "Cog-AgentRunner"); t.setDaemon(true); return t;
//                });

        private final AtomicBoolean schedulerRunning = new AtomicBoolean(true);
        private final RandomGenerator random = RandomGenerator.getDefault();

        AgentScheduler(Cog cog) { this.cog = cog; }

        void addAgent(Atom agentId, Game game, Atom goalPattern, int maxCycles) {
            cog.add(agentId); // Ensure agent ID atom exists in memory
            var context = new AgentContext(cog, new Agent(cog, agentId), game, goalPattern, maxCycles);
            activeAgents.put(agentId, context);
            // Initialize goal in MeTTa space for this agent
            context.agent.initializeGoal(goalPattern);
             System.out.println("AgentScheduler: Registered " + agentId + " (Game: " + game.gameId() + ")");
        }

        void removeAgent(Atom agentId) {
            var ctx = activeAgents.remove(agentId);
            if (ctx != null) System.out.println("AgentScheduler: Unregistered " + agentId);
        }

        Optional<Game> getGameForAgent(Atom agentId) {
            return Optional.ofNullable(activeAgents.get(agentId)).map(ctx -> ctx.game);
        }

        @Nullable AgentContext getAgentContext(Atom agentId) { return activeAgents.get(agentId); }

        /** Main scheduling loop logic, called periodically by systemScheduler. */
        void runSchedulingCycle() {
            if (!schedulerRunning.get() || activeAgents.isEmpty()) return;

            long now = cog.getLogicalTime(); // Use Cog's logical time for priorities

            // 1. Calculate priorities and total priority sum for active, non-halted agents
            var candidates = activeAgents.values().stream()
                .filter(ctx -> ctx.isRunning.get() && !ctx.isGoalMet.get()) // Only consider active agents not done
                .map(ctx -> new Pair<>(ctx, cog.mem.valueOrDefault(ctx.agentId).getCurrentPri(now)))
                .filter(p -> p.b > 0) // Exclude agents with zero priority
                .toList();

            if (candidates.isEmpty()) return;

            double totalPriority = candidates.stream().mapToDouble(Pair::b).sum();
            if (totalPriority <= 0) return; // No agents with positive priority

            // 2. Select agents to run this cycle based on weighted probability
            List<AgentContext> agentsToRun = new ArrayList<>();
            // Simple weighted random sampling without replacement
            List<Pair<AgentContext, Double>> weightedCandidates = new ArrayList<>(candidates);
            for (int i = 0; i < Math.min(AGENT_SCHEDULER_STEPS_PER_INTERVAL, candidates.size()); i++) {
                 if (weightedCandidates.isEmpty()) break;
                 double currentTotalW = weightedCandidates.stream().mapToDouble(Pair::b).sum();
                 if (currentTotalW <= 0) break;
                 double r = random.nextDouble() * currentTotalW;
                 double cumulative = 0;
                 int selectedIdx = -1;
                 for(int j=0; j<weightedCandidates.size(); ++j) {
                     cumulative += weightedCandidates.get(j).b;
                     if (r <= cumulative) {
                         selectedIdx = j; break;
                     }
                 }
                 if (selectedIdx != -1) {
                     agentsToRun.add(weightedCandidates.remove(selectedIdx).a);
                 } else { // Should not happen with positive weights, but handle defensively
                      if (!weightedCandidates.isEmpty()) agentsToRun.add(weightedCandidates.remove(random.nextInt(weightedCandidates.size())).a);
                 }
            }


            // 3. Execute one cycle for each selected agent asynchronously
            agentsToRun.forEach(context -> agentExecutor.submit(() -> executeAgentCycle(context)));
        }

        /** Executes a single perception-action-learning cycle for an agent. */
        private void executeAgentCycle(AgentContext context) {
            if (!context.isRunning.get() || context.isGoalMet.get()) return; // Double check status

            try {
                var cycles = context.cyclesCompleted.incrementAndGet();
                // System.out.printf("Agent %s Cycle %d Start%n", context.agentId, cycles); // Reduce log noise

                // Delegate core logic to Agent instance
                Atom cycleResult = context.agent.runCycle(context.game, context.goalPattern, context.lastStateAtom);

                // Update agent status after cycle
                boolean goalMet = context.agent.checkGoal(context.goalPattern);
                context.isGoalMet.set(goalMet);

                boolean gameRunning = context.game.isRunning();
                boolean maxCyclesReached = (context.maxCycles != -1 && cycles >= context.maxCycles);

                if (goalMet || !gameRunning || maxCyclesReached) {
                    context.isRunning.set(false); // Stop agent execution
                    var reason = goalMet ? "Goal Met" : (!gameRunning ? "Game Ended" : "Max Cycles");
                    System.out.printf("AgentScheduler: Stopping Agent %s (%s). Final State: %s%n", context.agentId, reason, context.lastStateAtom.get());
                }
                // System.out.printf("Agent %s Cycle %d End (GoalMet=%s, Running=%s)%n", context.agentId, cycles, goalMet, context.isRunning.get());

            } catch (Exception e) {
                System.err.printf("ERROR during agent %s cycle %d: %s%n", context.agentId, context.cyclesCompleted.get(), e.getMessage());
                e.printStackTrace();
                context.isRunning.set(false); // Halt agent on error
            }
        }

        /** Stops the scheduler and shuts down the executor. */
        void stop() {
            schedulerRunning.set(false);
            agentExecutor.shutdown();
            try {
                if (!agentExecutor.awaitTermination(1, TimeUnit.SECONDS)) agentExecutor.shutdownNow();
            } catch (InterruptedException e) {
                agentExecutor.shutdownNow(); Thread.currentThread().interrupt();
            }
            System.out.println("AgentScheduler stopped.");
        }
    }

    /** Agent logic handler. Executes one cycle when called by the scheduler. */
    public class Agent {
        private final Cog cog;
        private final Atom id; // This agent's symbolic ID (e.g., Self, Agent_1)
        private final Atom agentStepExpr; // Expression evaluated each cycle, e.g., (AgentStep Agent_1)

        public Agent(Cog cog, Atom id) {
            this.cog = cog;
            this.id = id;
            this.agentStepExpr = cog.E(cog.S("AgentStep"), id); // Agent-specific step expression
            // Boost agent concepts (done once globally in Core/AgentScheduler now)
        }

        /** Executes one agent cycle: evaluate step expression, check goal. Called by AgentScheduler. */
        Atom runCycle(Game g, Atom goalPattern, AtomicReference<Atom> lastStateRef) {
            long time = cog.tick();
             // Evaluate the main agent step expression using MeTTa rules
             // System.out.println("Agent " + agentId + ": Evaluating step expr: " + agentStepExpr); // Reduce noise
             var stepResults = cog.eval(agentStepExpr); // <<< MeTTa drives the internal logic >>>

             // Attempt to find the state from the result (e.g., if AgentLearn returns it) or query it
             Atom currentState = findStateFromResult(stepResults);
             if (currentState == null) {
                  currentState = queryCurrentState(); // Fallback query
             }
             if (currentState != null) lastStateRef.set(currentState); // Update shared state ref


             // Log primary result (optional, can be noisy)
             /*
             stepResults.stream()
                 .filter(r -> !r.equals(agentStepExpr))
                 .findFirst()
                 .ifPresent(result -> System.out.println("Agent " + agentId + ": Step result -> " + result));
             */

             // Return a representative result atom from the step evaluation
             return stepResults.stream().findFirst().orElse(SYM_NIL);
        }

        /** Checks if the agent's goal is currently met according to MeTTa space. */
        boolean checkGoal(Atom goalPattern) {
            Atom goalCheckExpr = cog.E(cog.S("CheckGoal"), id, goalPattern);
            return cog.evalBest(goalCheckExpr).filter(SYM_TRUE::equals).isPresent();
        }

        /** Adds the goal representation to memory for this agent. */
        void initializeGoal(Atom goalPattern) {
            var goalAtom = cog.add(cog.E(cog.S("Goal"), id, goalPattern));
            cog.mem.updateTruth(goalAtom, Truth.TRUE);
            cog.mem.boost(goalAtom, PRI_BOOST_ON_GOAL_FOCUS);
            cog.mem.boost(goalPattern, PRI_BOOST_ON_GOAL_FOCUS * 0.8f); // Also boost pattern
            // System.out.println("Agent " + agentId + ": Goal initialized -> " + goalAtom); // Reduce noise
        }

        /** Tries to extract a state atom (e.g., (State AgentId ...)) from evaluation results. */
        private @Nullable Atom findStateFromResult(List<Atom> results) {
             // Look for atoms matching (State <agentId> ...) structure, or potentially
             // results from AgentExecute like (Act <perceptsIs> ...) where percepts contain the state.
             return results.stream()
                 .flatMap(res -> {
                     if (res instanceof Expr e && e.head().equals(cog.S("State")) && e.children.size() > 1 && e.children.get(1).equals(id)) {
                         return Stream.of(res); // Found direct state atom
                     }
                     if (res instanceof Expr e && e.head().equals(cog.S("Act")) && e.children.size() > 1) {
                         // Look inside the percepts list Is<List<Atom>>
                         return Core.listValue(e.children.get(1)) // Get List<Atom> from Is
                                .stream().flatMap(List::stream) // Stream atoms in the list
                                .filter(p -> p instanceof Expr pe && pe.head().equals(cog.S("State")) && pe.children.size() > 1 && pe.children.get(1).equals(id));
                     }
                     return Stream.empty();
                 })
                 .findFirst().orElse(null);
        }

        /** Queries MeTTa space for the agent's current state atom. */
         private @Nullable Atom queryCurrentState() {
             Atom statePattern = cog.E(cog.S("State"), id, cog.V("currentStateVal"));
             return cog.query(statePattern).stream()
                       .map(Answer::resultAtom)
                       .max(Comparator.comparingDouble(a -> cog.mem.valueOrDefault(a).getWeightedTruth())) // Get highest truth state
                       .orElse(null);
         }
    }

    // --- Test Class for Java Field Access Demo ---
    public static class TestClass {
        public String myField = "InitialValue"; public static final String staticField = "StaticInitial";
        public TestClass() {}
        @Override public String toString() { return "TestClass[myField=" + myField + "]"; }
    }

}
