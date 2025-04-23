package dumb.pln;

import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.empty;

/**
 * Cog - Cognitive Logic Engine (CLE) - Derived from OpenCog and NARS
 * <p>
 * Represents a significantly enhanced and refactored Probabilistic Logic Network implementation,
 * designed for general intelligence research, emphasizing adaptability, autonomy, and cognitive power.
 * It recursively integrates its components and knowledge using its own representational framework ("dogfooding").
 * <p>
 * ## Core Design Principles:
 * - **Unified Representation:** All concepts, relations, events, goals, plans, and even system states
 * are represented uniformly as `Atom` instances (Nodes or Links).
 * - **Probabilistic & Temporal:** Core reasoning uses probabilistic truth values (`Truth`) and
 * explicit temporal scopes (`TimeSpec`).
 * - **Importance-Driven Attention:** `Atom` importance (STI/LTI) guides attention allocation, infer focus,
 * learning priorities, and memory management (forgetting).
 * - **Recursive Integration:** Inference rules operate on Atoms, potentially producing new Atoms representing
 * derived knowledge, plans, or beliefs. The Agent uses infer and planning (which use the memory)
 * to interact with the Environment, learning new Atoms that refine its memory and future behavior.
 * - **Metaprogrammatic Potential:** The structure allows representing agent goals, beliefs, infer strategies,
 * or system states as Atoms, enabling meta-reasoning (though advanced meta-reasoning requires further rule development).
 * - **Dynamic & Hierarchical:** Knowledge is added dynamically. Links create hierarchical structures (e.g., Evaluation links
 * predicating on other links or nodes).
 * - **Consolidated & Modular:** Delivered as a single, self-contained file using nested static classes for logical modularity.
 * - **Modern Java:** Leverages Records, Streams, VarHandles (for efficient volatile access), Concurrent Collections, etc.
 * <p>
 * ## Key Enhancements over Previous Versions:
 * - **Refined Naming:** Identifiers are concise, accurate, and follow lower-level descriptors (e.g., `Truth`, `Atom`, `Node`, `Link`).
 * - **Enhanced `Truth`:** More robust merging logic.
 * - **Improved Importance:** Uses `VarHandle` for atomic updates, refined decay/boost logic, and integration with confidence.
 * - **Advanced Memory:** More efficient indexing, robust atom revision, integrated time provider.
 * - **Sophisticated Inference:**
 * - More robust rule implementations (Deduction, Inversion, Modus Ponens).
 * - Basic unify implemented and integrated into Backward Chaining.
 * - More explicit handling of Temporal Rules (basic Persistence, Effect application).
 * - Forward Chaining prioritizes based on combined importance and confidence.
 * - **Enhanced Planning:** More robust recursive planner, better integration of preconditions, handles action schemas.
 * - **Autonomous Agent:** More sophisticated state representation (`perceiveState`), improved action selection (planning + reactive utility),
 * enhanced learning (state transitions, reward/utility association, basic reinforcement).
 * - **Dogfooding Examples:** Goals, state representations, and learned associations are directly represented as Atoms/Links.
 * - **Code Quality:** Improved structure, clarity, efficiency (e.g., VarHandles), and adherence to modern Java practices. Reduced redundancy.
 *
 * @version 2.0 Synthesis
 */
public final class Cog {

    // --- Configuration Constants ---
    /**
     * aka Horizon
     */
    private static final double TRUTH_DEFAULT_SENSITIVITY = 1.0; // k in confidence = count / (count + k)
    private static final double TRUTH_MIN_CONFIDENCE_FOR_VALID_EVIDENCE = 0.05;
    private static final double TRUTH_MIN_STRENGTH_FOR_RELEVANCE = 0.01;

    // Logical
    private static final double INFER_CONFIDENCE_DISCOUNT = 0.9; // General uncertainty factor
    private static final double INFER_TEMPORAL_DISCOUNT = 0.8; // Higher uncertainty for temporal projection
    private static final int INFER_DEFAULT_MAX_DEPTH = 5;
    private static final int FORWARD_CHAINING_BATCH_SIZE = 50;

    // Importance / Attention / Forgetting
    private static final double IMPORTANCE_MIN_FORGET_THRESHOLD = 0.02; // Combined STI/LTI threshold for forgetting
    private static final double IMPORTANCE_INITIAL_STI = 0.1;
    private static final double IMPORTANCE_INITIAL_LTI_FACTOR = 0.1; // LTI starts as a fraction of initial STI
    private static final double IMPORTANCE_STI_DECAY_RATE = 0.05; // Multiplicative decay per cycle/access
    private static final double IMPORTANCE_LTI_DECAY_RATE = 0.005; // Slower LTI decay
    private static final double IMPORTANCE_STI_TO_LTI_RATE = 0.02; // Rate LTI learns from STI
    private static final double IMPORTANCE_BOOST_ON_ACCESS = 0.05; // STI boost when Atom is used/retrieved
    private static final double IMPORTANCE_BOOST_ON_REVISION_MAX = 0.4; // Max STI boost from significant revision
    private static final double IMPORTANCE_BOOST_ON_GOAL_FOCUS = 0.9; // STI boost for active goals
    private static final double IMPORTANCE_BOOST_ON_PERCEPTION = 0.7; // STI boost for directly perceived atoms
    private static final double IMPORTANCE_BOOST_INFERRED_FACTOR = 0.3; // How much importance is inherited during infer
    private static final long FORGETTING_CHECK_INTERVAL_MS = 10000; // Check every 10 seconds
    private static final int FORGETTING_MAX_MEM_SIZE_TRIGGER = 15000; // Check if mem exceeds this size
    private static final int FORGETTING_TARGET_MEM_SIZE_FACTOR = 80; // Target size as % of max after forgetting
    private static final Set<String> PROTECTED_NODE_NAMES = Set.of("Reward", "GoodAction", "Self", "Goal"); // Core concepts

    // Planning & Agent
    private static final int PLANNING_DEFAULT_MAX_PLAN_DEPTH = 8; // Max actions in a plan
    private static final double AGENT_DEFAULT_PERCEPTION_COUNT = 5.0; // Default evidence count for perceived facts
    private static final double AGENT_DEFAULT_LEARNING_COUNT = 1.0; // Default evidence count for learned associations
    private static final double AGENT_UTILITY_THRESHOLD_FOR_SELECTION = 0.1;
    private static final double AGENT_RANDOM_ACTION_PROBABILITY = 0.05; // Epsilon for exploration

    private static final String VAR_PREFIX = "?"; // SPARQL-like prefix for vars

    private final Memory mem;
    private final Infer infer;
    private final Agent agent;
    private final ScheduledExecutorService exe;

    /**
     * logical, monotonic time for internal ordering/recency
     */
    private final AtomicLong iteration = new AtomicLong(0);

    public Cog() {
        this.mem = new Memory(this::iteration);
        this.infer = new Infer(this.mem);
        this.agent = new Agent();
        this.exe = Executors.newSingleThreadScheduledExecutor(r -> {
            /* periodic maintenance (forgetting, importance decay) */
            var t = new Thread(r, "maintenance");
            t.setDaemon(true);
            return t;
        });
        exe.scheduleAtFixedRate(this::performMaintenance, FORGETTING_CHECK_INTERVAL_MS, FORGETTING_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);

        System.out.println(Cog.class + " Initialized.");
    }

    // --- Public Facade API ---


    // === Main Method (Comprehensive Demonstration)                       ===

    public static void main(String[] args) {
        var cle = new Cog();
        try {
            System.out.println("\n--- Cognitive Logic Engine Demonstration ---");

            // --- 1. Basic Knowledge & Forward Chaining ---
            System.out.println("\n[1] Basic Knowledge & Forward Chaining:");
            var cat = cle.node("Cat");
            var mammal = cle.node("Mammal");
            var animal = cle.node("Animal");
            var dog = cle.node("Dog");
            var predator = cle.node("Predator");
            var hasClaws = cle.node("HasClaws");

            cle.learn(Type.INHERITANCE, Truth.of(0.95, 20), 0.8, cat, mammal);
            cle.learn(Type.INHERITANCE, Truth.of(0.98, 50), 0.9, mammal, animal);
            cle.learn(Type.INHERITANCE, Truth.of(0.90, 30), 0.8, dog, mammal);
            cle.learn(Type.INHERITANCE, Truth.of(0.7, 10), 0.6, cat, predator);
            cle.learn(Type.INHERITANCE, Truth.of(0.8, 15), 0.7, predator, hasClaws);

            cle.forward(2); // Run infer

            System.out.println("\nChecking derived knowledge:");
            var dogAnimalLinkId = Link.id(Type.INHERITANCE, List.of(dog.id, animal.id));
            cle.retrieveAtom(dogAnimalLinkId).ifPresentOrElse(atom -> System.out.println(" -> Derived Dog->Animal: " + atom), () -> System.out.println(" -> Derived Dog->Animal: Not found (Expected)."));

            var catClawsLinkId = Link.id(Type.INHERITANCE, List.of(cat.id, hasClaws.id));
            cle.retrieveAtom(catClawsLinkId).ifPresentOrElse(atom -> System.out.println(" -> Derived Cat->HasClaws: " + atom), () -> System.out.println(" -> Derived Cat->HasClaws: Not found (Expected)."));

            // --- 2. Backward Chaining Query & unify ---
            System.out.println("\n[2] Backward Chaining & unify:");
            var varX = cle.var("X");
            var chasesPred = cle.node("Predicate:Chases"); // Use prefix convention
            var ball = cle.node("Ball");
            // Define 'Dog chases Ball' using an Evaluation link: Evaluation(Chases, Dog, Ball)
            cle.learn(Type.EVALUATION, Truth.of(0.85, 10), 0.7, chasesPred, dog, ball);

            // Query: Evaluation(Chases, ?X, Ball) - What chases the ball?
            Atom queryPattern = new Link(Type.EVALUATION, List.of(chasesPred.id, varX.id, ball.id), Truth.UNKNOWN, null);
            System.out.println("Query: " + queryPattern.id);
            var results = cle.query(queryPattern, 3);
            if (results.isEmpty()) {
                System.out.println(" -> No results found.");
            } else {
                results.forEach(res -> System.out.println(" -> Result: " + res));
            }

            // --- 3. Temporal Logic & Planning ---
            System.out.println("\n[3] Temporal Logic & Planning:");
            var agentNode = cle.node("Self"); // Standard node for the agent
            var key = cle.node("Key");
            var door = cle.node("Door");
            var locationA = cle.node("LocationA");
            var locationB = cle.node("LocationB");
            var hasKeyFluent = cle.node("Fluent:HasKey");
            var doorOpenFluent = cle.node("Fluent:DoorOpen");
            var atLocationPred = cle.node("Predicate:AtLocation");
            var pickupAction = cle.node("Action:PickupKey");
            var openAction = cle.node("Action:OpenDoor");
            var moveToBAction = cle.node("Action:MoveToB");

            // Initial State (Time = 0 assumed for simplicity in setup)
            var t0 = cle.iteration(); // Or cle.incrementLogicalTime();
            // HoldsAt(Predicate:AtLocation(Self, LocationA), T0)
            var agentAtA = cle.learn(Type.HOLDS_AT, Truth.TRUE, 1.0, Time.at(t0), cle.learn(Type.EVALUATION, Truth.TRUE, 1.0, atLocationPred, agentNode, locationA));
            // NOT HoldsAt(Fluent:HasKey(Self), T0) - Represented by low strength/confidence
            var notHasKey = cle.learn(Type.HOLDS_AT, Truth.FALSE, 1.0, Time.at(t0), cle.learn(Type.EVALUATION, Truth.TRUE, 1.0, hasKeyFluent, agentNode)); // Evaluation represents the state itself
            // NOT HoldsAt(Fluent:DoorOpen(Door), T0)
            var notDoorOpen = cle.learn(Type.HOLDS_AT, Truth.FALSE, 1.0, Time.at(t0), cle.learn(Type.EVALUATION, Truth.TRUE, 1.0, doorOpenFluent, door));

            // Rules (Simplified - Using Predictive Implication for planning effects)
            // Precondition: HoldsAt(AtLocation(Self, LocationA))
            Atom precondPickup = agentAtA; // Use the state link directly
            // Effect: HoldsAt(HasKey(Self))
            Atom effectPickup = cle.learn(Type.HOLDS_AT, Truth.TRUE, 0.9, // Effect will be true
                    cle.learn(Type.EVALUATION, Truth.TRUE, 1.0, hasKeyFluent, agentNode));
            // Rule: Sequence(Precond, Action) => Effect [Time: range]
            var pickupSeq = cle.learn(Type.SEQUENCE, Truth.TRUE, 0.9, precondPickup, pickupAction);
            cle.learn(Type.PREDICTIVE_IMPLICATION, Truth.of(0.95, 10), 0.9, Time.range(100), pickupSeq, effectPickup);

            // Precondition: HoldsAt(HasKey(Self))
            var precondOpen = effectPickup; // Use the effect of the previous action's potential outcome
            // Effect: HoldsAt(DoorOpen(Door))
            Atom effectOpen = cle.learn(Type.HOLDS_AT, Truth.TRUE, 0.9, cle.learn(Type.EVALUATION, Truth.TRUE, 1.0, doorOpenFluent, door));
            // Rule: Sequence(Precond, Action) => Effect
            var openSeq = cle.learn(Type.SEQUENCE, Truth.TRUE, 0.9, precondOpen, openAction);
            cle.learn(Type.PREDICTIVE_IMPLICATION, Truth.of(0.9, 10), 0.9, Time.range(200), openSeq, effectOpen);

            // Precondition: HoldsAt(DoorOpen(Door))
            var precondMove = effectOpen;
            // Effect: HoldsAt(AtLocation(Self, LocationB))
            Atom effectMove = cle.learn(Type.HOLDS_AT, Truth.TRUE, 0.9, cle.learn(Type.EVALUATION, Truth.TRUE, 1.0, atLocationPred, agentNode, locationB));
            // Rule: Sequence(Precond, Action) => Effect
            var moveSeq = cle.learn(Type.SEQUENCE, Truth.TRUE, 0.9, precondMove, moveToBAction);
            cle.learn(Type.PREDICTIVE_IMPLICATION, Truth.of(0.98, 10), 0.9, Time.range(300), moveSeq, effectMove);


            // Goal: Agent At Location B -> HoldsAt(AtLocation(Self, LocationB))
            var goal = effectMove;
            // Boost goal importance
            cle.retrieveAtom(goal.id).ifPresent(g -> g.boost(IMPORTANCE_BOOST_ON_GOAL_FOCUS, cle.iteration()));

            System.out.println("\nPlanning to achieve goal: " + goal.id);
            var planOpt = cle.plan(goal, 5, 3); // Max 5 actions, max 3 search depth for preconditions

            planOpt.ifPresentOrElse(plan -> {
                System.out.println("Plan Found:");
                plan.forEach(action -> System.out.println(" -> " + action.id));
            }, () -> System.out.println("No plan found."));

            // --- 4. Agent Simulation (Simple Grid World) ---
            System.out.println("\n[4] Agent Simulation (Basic Grid World):");
            var gridWorld = new BasicGridWorld(cle, 4, 2, 2); // 4x4 grid, goal at (2,2)
            var goalLocationNode = cle.node("Pos_2_2");
            var agentAtPred = precondPickup; //???
            Atom goalState = cle.learn(Type.HOLDS_AT, Truth.TRUE, 1.0, cle.learn(Type.EVALUATION, Truth.TRUE, 1.0, agentAtPred, agentNode, goalLocationNode));
            cle.run(gridWorld, goalState, 20); // Run agent for max 20 steps


            // --- 5. Forgetting Check ---
            System.out.println("\n[5] Forgetting Check:");
            var sizeBefore = cle.mem.size();
            System.out.println("Mem size before wait: " + sizeBefore);
            System.out.println("Waiting for maintenance cycle...");
            try {
                Thread.sleep(FORGETTING_CHECK_INTERVAL_MS + 1000);
            } catch (InterruptedException ignored) {
            }
            var sizeAfter = cle.mem.size();
            System.out.println("Mem size after wait: " + sizeAfter + " (Removed: " + (sizeBefore - sizeAfter) + ")");


        } catch (Exception e) {
            System.err.println("\n--- ERROR during demonstration ---");
            e.printStackTrace();
        } finally {
            cle.shutdown(); // Ensure scheduler is stopped
        }
        System.out.println("\n--- Cognitive Logic Engine Demonstration Finished ---");
    }

    public static double unitize(double initialSTI) {
        return Math.max(0.0, Math.min(1.0, initialSTI));
    }

    /**
     * Provides the current logical time step.
     */
    public long iteration() {
        return iteration.get();
    }

    /**
     * Adds or revises an Atom in the Knowledge Base.
     * Handles truth merging, importance updates, and indexing.
     *
     * @param atom The Atom to add or revise.
     * @return The resulting Atom in the memory (potentially the existing one after revision).
     */
    public Atom learn(Atom atom) {
        return mem.learn(atom);
    }

    /**
     * Retrieves an Atom by its unique ID, boosting its importance.
     *
     * @param id The unique ID of the Atom.
     * @return An Optional containing the Atom if found, empty otherwise.
     */
    public Optional<Atom> retrieveAtom(String id) {
        return mem.atom(id);
    }

    /**
     * Gets or creates a Node Atom by its name.
     * If created, uses default truth and initial importance.
     *
     * @param name The conceptual name of the node.
     * @return The existing or newly created Node.
     */
    public Node node(String name) {
        return mem.node(name, Truth.UNKNOWN, IMPORTANCE_INITIAL_STI);
    }

    /**
     * Gets or creates a Node Atom with specified initial truth and importance.
     *
     * @param name       The conceptual name.
     * @param t          Initial truth.
     * @param initialSTI Initial Short-Term Importance.
     * @return The existing or newly created Node.
     */
    public Node node(String name, Truth t, double initialSTI) {
        return mem.node(name, t, initialSTI);
    }

    /**
     * Gets or creates a Var, which are typically protected from forgetting.
     *
     * @param name The var name (without prefix).
     * @return The existing or newly created Var.
     */
    public Var var(String name) {
        return mem.var(name);
    }

    /**
     * Creates and learns a Link Atom connecting target Atoms.
     *
     * @return The learned Link Atom.
     */
    public Link learn(Type type, Truth t, double initialSTI, Atom... targets) {
        return learn(type, t, initialSTI, null, targets);
    }

    /**
     * Creates and learns a Link Atom with an associated TimeSpec.
     *
     * @return The learned Link Atom.
     */
    public Link learn(Type type, Truth t, double initialSTI, Time time, Atom... targets) {
        return mem.link(type, t, initialSTI, time, targets);
    }

    /**
     * Creates and learns a Link Atom using target Atom IDs.
     * Less safe if IDs might not exist, but convenient.
     *
     * @param type       The type of relationship.
     * @param t          The initial truth of the link.
     * @param initialSTI Initial Short-Term Importance.
     * @param targetIds  The IDs of the target Atoms.
     * @return The learned Link Atom, or null if target IDs are invalid.
     */
    public Link learn(Type type, Truth t, double initialSTI, String... targetIds) {
        return mem.link(type, t, initialSTI, null, targetIds);
    }

    /**
     * Creates and learns a Link Atom with a TimeSpec, using target Atom IDs.
     *
     * @param type       The type of relationship.
     * @param t          The initial truth of the link.
     * @param initialSTI Initial Short-Term Importance.
     * @param time       The temporal specification.
     * @param targetIds  The IDs of the target Atoms.
     * @return The learned Link Atom, or null if target IDs are invalid.
     */
    public Link learn(Type type, Truth t, double initialSTI, Time time, String... targetIds) {
        return mem.link(type, t, initialSTI, time, targetIds);
    }

    /**
     * Performs forward chaining infer, deriving new knowledge based on importance heuristics.
     *
     * @param maxSteps The maximum number of infer batches to perform.
     */
    public void forward(int maxSteps) {
        infer.forward(maxSteps);
    }

    /**
     * Queries the knowledge base using backward chaining to determine the truth of a pattern Atom.
     * Supports vars and unify.
     *
     * @param queryPattern The Atom pattern to query (can contain Var's).
     * @param maxDepth     Maximum recursion depth for the search.
     * @return A list of QueryResult records, each containing bind and the inferred Atom matching the query.
     */
    public List<Answer> query(Atom queryPattern, int maxDepth) {
        return infer.backward(queryPattern, maxDepth);
    }

    /**
     * Attempts to find a sequence of action Atoms (e.g., Action Nodes or Execution Links)
     * predicted to achieve the desired goal state Atom.
     *
     * @param goalPattern    The Atom pattern representing the desired goal state.
     * @param maxPlanDepth   Maximum length of the action sequence.
     * @param maxSearchDepth Maximum recursion depth for finding preconditions.
     * @return An Optional containing a list of action Atoms representing the plan, or empty if no plan found.
     */
    public Optional<List<Atom>> plan(Atom goalPattern, int maxPlanDepth, int maxSearchDepth) {
        return infer.planToActionSequence(goalPattern, maxPlanDepth, maxSearchDepth);
    }

    /**
     * Runs the agent's perceive-reason-act-learn cycle within a given environment.
     *
     * @param environment The environment the agent interacts with.
     * @param goal    The primary goal the agent tries to achieve.
     * @param maxCycles   The maximum number of cycles to run the agent.
     */
    public void run(Game environment, Atom goal, int maxCycles) {
        agent.run(environment, goal, maxCycles);
    }

    /**
     * periodic maintenance tasks like forgetting low-importance atoms and decaying importance.
     */
    private void performMaintenance() {
        mem.decayAndForget();
    }

    public void shutdown() {
        exe.shutdown();
        try {
            if (!exe.awaitTermination(5, TimeUnit.SECONDS)) {
                exe.shutdownNow();
            }
        } catch (InterruptedException e) {
            exe.shutdownNow();
            Thread.currentThread().interrupt();
        }
        System.out.println("Cognitive Logic Engine scheduler shut down.");
    }

    public enum Type {
        // Core Semantic Links
        INHERITANCE(false),     // IS-A (Subtype -> Supertype)
        SIMILARITY(true),       // Similar concepts
        INSTANCE(false),        // Instance -> Type
        MEMBER(false),          // Member -> Collection
        EVALUATION(false),      // Predicate applied to arguments (Predicate, Arg1, Arg2...)
        EXECUTION(false),       // Represents an action execution event (Action, Agent, Object?, Time?)

        // Logical Links
        AND(true), OR(true), NOT(false), // Boolean logic (Targets are propositions)
        PREDICTIVE_IMPLICATION(false), // If A then (likely/eventually) B (A, B)

        // Temporal Links (Often wrap other links/nodes)
        SEQUENCE(false),        // A occurs then B occurs (A, B)
        SIMULTANEOUS(true),     // A and B occur together (A, B)
        HOLDS_AT(false),        // Fluent/Predicate holds at a specific time/interval (FluentAtom, TimeSpecAtom?) - Often wraps Evaluation
        // Event Calculus Style (optional, requires specific temporal reasoning rules)
        INITIATES(false),       // Action initiates Fluent (Action, Fluent)
        TERMINATES(false),      // Action terminates Fluent (Action, Fluent)

        // Higher-Order Logic Links
        FOR_ALL(false),         // Universal quantifier (Var1, Var2..., BodyAtom)
        EXISTS(false);          // Existential quantifier (Var1, Var2..., BodyAtom)

        public final boolean commutative;

        Type(boolean commutative) {
            this.commutative = commutative;
        }
    }


    public interface Game {
        /**
         * Perceive the current state of the environment.
         */
        Percept perceive();

        /**
         * Get the list of actions currently possible for the agent.
         */
        List<Atom> actions(); // Should return Action schema Atoms (e.g., Nodes)

        /**
         * Execute the chosen action schema in the environment.
         */
        Act exe(Atom actionSchema);

        /**
         * Check if the environment simulation has not reached a terminal state.
         */
        boolean running();
    }


    public static class Memory {
        // Primary Atom store: ID -> Atom
        private final ConcurrentMap<String, Atom> atoms = new ConcurrentHashMap<>(256);
        // Indices for efficient link retrieval
        private final ConcurrentMap<Type, ConcurrentSkipListSet<String>> linksByType = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, ConcurrentSkipListSet<String>> linksByTarget = new ConcurrentHashMap<>();
        private final Supplier<Long> time;

        public Memory(Supplier<Long> time) {
            this.time = time;
            // Pre-populate link type index map for efficiency
            for (var type : Type.values())
                linksByType.put(type, new ConcurrentSkipListSet<>());
        }

        /**
         * Adds or updates an Atom, handling revision, importance, and indexing.
         */
        public <A extends Atom> A learn(A atom) {
            long now = time.get();
            var atomId = atom.id;

            var result = atoms.compute(atomId, (id, existing) -> {
                if (existing == null) {
                    atom.updateAccessTime(now);
                    atom.boost(IMPORTANCE_INITIAL_STI * 0.5, now); // Small boost on initial learn
                    return atom;
                } else {
                    // Perform revision: merge truth, update importance, merge TimeSpec
                    var oldTruth = existing.truth();
                    var revisedTruth = oldTruth.merge(atom.truth());
                    existing.truth(revisedTruth); // Update truth in place
                    existing.updateAccessTime(now);

                    var boost = revisionBoost(oldTruth, atom.truth(), revisedTruth);
                    existing.boost(boost + IMPORTANCE_BOOST_ON_ACCESS, now); // Boost on revision + access

                    if (existing instanceof Link existingLink && atom instanceof Link newLink)
                        existingLink.setTime(Time.merge(existingLink, newLink));

                    // Log revision? System.out.printf("Revised: %s -> %s%n", existingAtom.id(), revisedTruth);
                    return existing;
                }
            });

            // Update indices if it's a Link and it was newly added or potentially modified
            if (result instanceof Link link) update(link);

            // Trigger forgetting check if Memory grows too large (asynchronously)
            if (atoms.size() > FORGETTING_MAX_MEM_SIZE_TRIGGER) CompletableFuture.runAsync(this::decayAndForget);

            //noinspection unchecked
            return (A) result;
        }

        /**
         * Retrieves an Atom by ID, boosting its importance.
         */
        public Optional<Atom> atom(String id) {
            var atom = _atom(id);
            return Optional.ofNullable(atom);
        }

        private @Nullable Atom _atom(String id) {
            var atom = atoms.get(id);
            if (atom != null) atom.boost(IMPORTANCE_BOOST_ON_ACCESS, time.get());
            return atom;
        }

        public final Atom atomOrElse(String id, Atom orElse) {
            var a = _atom(id);
            return a == null ? orElse : a;
        }

        /**
         * Gets or creates a Node by name with specific initial state.
         */
        public Node node(String name, Truth truth, double initialSTI) {
            return (Node) atoms.computeIfAbsent(Node.id(name), id -> {
                var newNode = new Node(name, truth);
                newNode.pri(initialSTI, time.get());
                return newNode;
            });
        }

        public Node node(String name) {
            return (Node) _atom(Node.id(name));
        }

        /**
         * Gets or creates a vars
         */
        public Var var(String name) {
            var varId = Var.varID(name);
            return (Var) atoms.computeIfAbsent(varId, id -> {
                var vn = new Var(name);
                vn.pri(0.0, time.get()); // vars have 0 STI, high LTI implied by protection
                vn.lti(1); // Ensure LTI is high
                return vn;
            });
        }

        /**
         * Creates and learns a Link from Atom instances.
         */
        public Link link(Type type, Truth truth, double initialSTI, Time time, Atom... targets) {
            // Use List.of() or toList()
            return link(type, truth, initialSTI, time, Arrays.stream(targets).filter(Objects::nonNull).map(atom -> atom.id).toList());
        }

        /**
         * Creates and learns a Link from target IDs.
         */
        public Link link(Type type, Truth truth, double initialSTI, Time time, String... targetIds) {
            if (targetIds == null || targetIds.length == 0 || Arrays.stream(targetIds).anyMatch(Objects::isNull)) {
                System.err.printf("Warning: Attempted to create link type %s with null or empty targets.%n", type);
                return null; // Or throw exception
            }
            // Basic check if target IDs exist (optional, adds overhead)
            // for (String targetId : targetIds) { if (!atoms.containsKey(targetId)) { System.err.println("Warning: Link target missing: " + targetId); } }
            return link(type, truth, initialSTI, time, Arrays.asList(targetIds));
        }

        private Link link(Type type, Truth truth, double initialSTI, Time time, List<String> targetIds) {
            var link = new Link(type, targetIds, truth, time);
            link.pri(initialSTI, this.time.get());
            return learn(link); // Use learnAtom for revision/indexing logic
        }

        /**
         * Removes an Atom and updates indices. Needs external synchronization if called outside learnAtom's compute.
         */
        private void removeAtomInternal(String id) {
            var removed = atoms.remove(id);
            if (removed instanceof Link link) remove(link);
        }

        /**
         * Updates indices for a given link.
         */
        private void update(Link link) {
            linksByType.get(link.type).add(link.id); // Assumes type exists in map
            for (var targetId : link.targets) {
                linksByTarget.computeIfAbsent(targetId, k -> new ConcurrentSkipListSet<>()).add(link.id);
            }
        }

        /**
         * Removes indices for a given link.
         */
        private void remove(Link link) {
            Optional.ofNullable(linksByType.get(link.type)).ifPresent(set -> set.remove(link.id));
            for (var targetId : link.targets) {
                Optional.ofNullable(linksByTarget.get(targetId)).ifPresent(set -> set.remove(link.id));
                // Optional: Clean up empty target sets periodically?
                // if (linksByTarget.containsKey(targetId) && linksByTarget.get(targetId).isEmpty()) { linksByTarget.remove(targetId); }
            }
        }

        /**
         * Retrieves links of a specific type, boosting importance.
         */
        public Stream<Link> links(Type type) {
            var links = linksByType.get(type);
            return links.isEmpty() ? Stream.empty() : _links(links);
        }

        /**
         * Retrieves links that include a specific Atom ID as a target, boosting importance.
         */
        public Stream<Link> linksWithTarget(String targetId) {
            var links = linksByTarget.get(targetId);
            return links.isEmpty() ? Stream.empty() : _links(links);
        }

        private Stream<Link> _links(ConcurrentSkipListSet<String> links) {
            return links.stream().map(this::_atom).filter(Objects::nonNull) // Boost importance
                    .filter(Link.class::isInstance).map(Link.class::cast);
        }

        /**
         * Gets the current number of atoms in the Memory.
         */
        public int size() {
            return atoms.size();
        }

        /**
         * Implements importance decay and forgetting. Called periodically.
         */
        public synchronized void decayAndForget() {
            final long now = time.get();
            var initialSize = atoms.size();
            if (initialSize == 0) return;

            List<Atom> candidates = new ArrayList<>(atoms.values()); // Copy for safe iteration/removal
            var decayCount = 0;
            var removedCount = 0;

            // 1. Decay importance for all atoms
            for (var atom : candidates) {
                atom.decayImportance(now);
                decayCount++;
            }

            // 2. Forget low-importance atoms if Memory is large
            var targetSize = (FORGETTING_MAX_MEM_SIZE_TRIGGER * FORGETTING_TARGET_MEM_SIZE_FACTOR) / 100;
            if (initialSize > FORGETTING_MAX_MEM_SIZE_TRIGGER || initialSize > targetSize * 1.1) { // Check size triggers
                // Sort by current combined importance (ascending)
                candidates.sort(Comparator.comparingDouble(a -> a.getCurrentImportance(now)));

                var removalTargetCount = Math.max(0, initialSize - targetSize);

                for (var atom : candidates) {
                    if (removedCount >= removalTargetCount) break; // Removed enough

                    var currentImportance = atom.getCurrentImportance(now);
                    if (currentImportance < IMPORTANCE_MIN_FORGET_THRESHOLD) {
                        var isProtected = (atom instanceof Node node && PROTECTED_NODE_NAMES.contains(node.name)) || (atom instanceof Var);
                        // Add other protection logic? (e.g., part of active goal/plan - needs AgentController input)

                        if (!isProtected) {
                            removeAtomInternal(atom.id); // Use internal remove synchronized method
                            removedCount++;
                        }
                    } else {
                        // Since sorted, all remaining atoms have higher importance
                        break;
                    }
                }
            }

            if (removedCount > 0 || decayCount > 0) {
                System.out.printf("DEBUG: Maintenance Cycle: Decayed %d atoms. Removed %d atoms (below importance < %.3f). Memory size %d -> %d.%n", decayCount, removedCount, IMPORTANCE_MIN_FORGET_THRESHOLD, initialSize, atoms.size());
            }
        }

        private double revisionBoost(Truth prev, Truth next, Truth revisedC) {
            var strengthChange = Math.abs(revisedC.strength - prev.strength);
            var confidenceChange = Math.abs(revisedC.conf() - prev.conf());
            // Boost more for significant changes or high-confidence confirmations
            var boost = (strengthChange * 0.6 + confidenceChange * 0.4) * (1.0 + next.conf());
            return Math.min(IMPORTANCE_BOOST_ON_REVISION_MAX, boost); // Cap the boost
        }
    }


    public static class Infer {
        private final Memory mem;
        private final Unify unify;

        public Infer(Memory mem) {
            this.mem = mem;
            this.unify = new Unify(mem);
        }

        /**
         * PLN Deduction: (A->B, B->C) => A->C. Handles INHERITANCE, PREDICTIVE_IMPLICATION.
         */
        public Optional<Link> deduce(Link linkAB, Link linkBC) {
            if (linkAB.type != linkBC.type || !isValidBinaryLink(linkAB) || !isValidBinaryLink(linkBC)) return empty();

            var bId = linkAB.targets.get(1);
            if (!bId.equals(linkBC.targets.get(0))) return empty(); // Must chain A->B, B->C

            var aId = linkAB.targets.get(0);
            var cId = linkBC.targets.get(1);
            // Retrieving atoms boosts their importance
            var nodeA = _atom(aId);
            if (nodeA == null) return empty();
            var nodeB = _atom(bId);
            if (nodeB == null) return empty();
            var nodeC = _atom(cId);
            if (nodeC == null) return empty();

            var cAB = linkAB.truth();
            var cBC = linkBC.truth();
            var cB = nodeB.truth();
            var cC = nodeC.truth(); // Base rates //TODO utilize 'cC' somehow

            // Simplified Deduction strength formula (avoids potential division by zero issues in original complex formula)
            // sAC approx sAB * sBC (More complex versions exist, but are sensitive to priors)
            // Let's use a weighted average based on confidence, tending towards product for low confidence
            var cABstrength = cAB.strength;
            var cBCstrength = cBC.strength;
            var cBConf = cB.conf();
            var sAC = unitize((cABstrength * cBCstrength * (1 - cBConf)) // Product term
                    + (cABstrength * cBCstrength + (1 - cABstrength) * cBCstrength * cAB.conf()) * cBConf // Towards implication
            );

            var discount = (linkAB.type == Type.PREDICTIVE_IMPLICATION) ? INFER_TEMPORAL_DISCOUNT : INFER_CONFIDENCE_DISCOUNT;
            var nAC = discount * Math.min(cAB.evi, cBC.evi) * cBConf; // Confidence depends on middle term's confidence
            var cAC = Truth.of(sAC, nAC);

            if (cAC.conf() < TRUTH_MIN_CONFIDENCE_FOR_VALID_EVIDENCE) return empty();

            var timeAC = (linkAB.type == Type.PREDICTIVE_IMPLICATION) ? Time.compose(linkAB.time(), linkBC.time()) : null;

            var inferredLink = new Link(linkAB.type, List.of(aId, cId), cAC, timeAC);

            var inferredSTI = calculateInferredImportance(linkAB, linkBC);
            inferredLink.pri(inferredSTI, mem.time.get());

            return Optional.of(mem.learn(inferredLink));
        }

        private Optional<Atom> atom(String aId) {
            return mem.atom(aId);
        }

        @Nullable
        private Atom _atom(String aId) {
            return mem._atom(aId);
        }

        /**
         * PLN Inversion (Abduction/Bayes): (A->B) => (B->A). Handles INHERITANCE, PREDICTIVE_IMPLICATION.
         */
        private Optional<Link> invert(Link linkAB) {
            if (!isValidBinaryLink(linkAB)) return empty();

            var aId = linkAB.targets.getFirst();
            var nodeA = _atom(aId);
            if (nodeA == null) return empty();

            var bId = linkAB.targets.get(1);
            var nodeB = _atom(bId);
            if (nodeB == null) return empty();


            var cAB = linkAB.truth();
            var cA = nodeA.truth();
            var cB = nodeB.truth();

            // Bayes' Rule: s(B->A) = s(A->B) * s(A) / s(B)
            double sBA;
            double nBA;
            if (cB.strength < 1e-9) { // Avoid division by zero; P(B) approx 0
                sBA = 0.5; // Maximum uncertainty
                nBA = 0.0; // No evidence
            } else {
                sBA = Math.max(0.0, Math.min(1.0, cAB.strength * cA.strength / cB.strength)); // Clamp result
                var discount = (linkAB.type == Type.PREDICTIVE_IMPLICATION) ? INFER_TEMPORAL_DISCOUNT : INFER_CONFIDENCE_DISCOUNT;
                // Confidence depends on confidence of all terms in Bayes theorem
                nBA = discount * cAB.evi * cA.conf() * cB.conf();
            }
            var cBA = Truth.of(sBA, nBA);

            if (cBA.conf() < TRUTH_MIN_CONFIDENCE_FOR_VALID_EVIDENCE) return empty();

            var timeBA = (linkAB.type == Type.PREDICTIVE_IMPLICATION) ? linkAB.time().inverse() : null;
            var inferredSTI = calculateInferredImportance(linkAB);

            var inferredLink = new Link(linkAB.type, List.of(bId, aId), cBA, timeBA); // Reversed targets
            inferredLink.pri(inferredSTI, mem.time.get());
            return Optional.of(mem.learn(inferredLink));
        }

        /**
         * Probabilistic Modus Ponens: (A, A->B) => Update B.
         */
        public Optional<Atom> modusPonens(Atom premiseA, Link implicationAB) {
            if (premiseA == null || !isValidBinaryLink(implicationAB) || !implicationAB.targets.get(0).equals(premiseA.id))
                return empty();

            var conclusionB = _atom(implicationAB.targets.get(1));
            if (conclusionB == null) return empty(); // Conclusion atom must exist to be updated

            var cA = premiseA.truth();
            var cAB = implicationAB.truth();

            // Calculate the evidence for B provided *by this specific infer*
            // s(B|A) = s(A->B) [Assuming independence, simple projection] - More sophisticated needed for complex PLN
            // Derived Strength: P(B) derived = P(A) * P(B|A) = s(A) * s(A->B)
            var derived_sB = unitize(cA.strength * cAB.strength);

            var discount = (implicationAB.type == Type.PREDICTIVE_IMPLICATION) ? INFER_TEMPORAL_DISCOUNT : INFER_CONFIDENCE_DISCOUNT;
            // Derived Count: Confidence depends on both premise and rule confidence
            var derived_nB = discount * Math.min(cA.evi, cAB.evi);

            if (derived_nB < 1e-6) return empty(); // Negligible evidence

            var evidenceTruth = Truth.of(derived_sB, derived_nB);

            // Create a temporary atom representing just this new piece of evidence for B
            var evidenceForB = conclusionB.withTruth(evidenceTruth);
            evidenceForB.pri(calculateInferredImportance(premiseA, implicationAB), mem.time.get());

            // Learn this new evidence, merging it with the existing truth of B
            var revisedB = mem.learn(evidenceForB);

            // Log infer? System.out.printf("  MP: %s, %s => %s revised to %s%n", premiseA.id(), implicationAB.id(), revisedB.id(), revisedB.truth());
            return Optional.of(revisedB);
        }

        /**
         * Performs forward chaining using importance heuristics.
         */
        public void forward(int steps) {
            // System.out.println("--- Infer: Starting Forward Chaining ---");
            Set<String> executedInferSignatures = new HashSet<>(); // Avoid redundant immediate re-computation
            var totalInfers = 0;

            for (var step = 0; step < steps; step++) {
                var queue = collectPotentialInfer();
                if (queue.isEmpty()) {
                    // System.out.println("FC Step " + (step + 1) + ": No potential infers found.");
                    break;
                }

                var infersThisStep = exeTopInfers(queue, executedInferSignatures);
                totalInfers += infersThisStep;
                // System.out.println("FC Step " + (step + 1) + ": Made " + infersThisStep + " infers.");

                if (infersThisStep == 0) {
                    // System.out.println("FC Step " + (step + 1) + ": Quiescence reached.");
                    break;
                }
            }
            if (totalInfers > 0)
                System.out.printf("--- Infer: Forward Chaining finished. Total infers: %d ---%n", totalInfers);
        }

        /**
         * Collects potential infers, sorted by importance.
         */
        private PriorityQueue<Step> collectPotentialInfer() {
            var queue = new PriorityQueue<>(Comparator.<Step, Double>comparing(inf -> inf.priority).reversed());
            final long now = mem.time.get();

            Predicate<Atom> isConfidentEnough = a -> a.truth().conf() > TRUTH_MIN_CONFIDENCE_FOR_VALID_EVIDENCE;

            // Collect Deductions (INHERITANCE, PREDICTIVE_IMPLICATION)
            Stream.of(Type.INHERITANCE, Type.PREDICTIVE_IMPLICATION).flatMap(mem::links).filter(this::isValidBinaryLink).filter(isConfidentEnough).forEach(linkAB -> {
                var bId = linkAB.targets.get(1);
                mem.linksWithTarget(bId) // Find links *starting* with B: B->C
                        .filter(linkBC -> linkBC.type == linkAB.type && isValidBinaryLink(linkBC) && linkBC.targets.getFirst().equals(bId)).filter(isConfidentEnough).forEach(linkBC -> queue.add(new Step(Rule.DEDUCTION, now, linkAB, linkBC)));
            });

            // Collect Inversions
            Stream.of(Type.INHERITANCE, Type.PREDICTIVE_IMPLICATION).flatMap(mem::links).filter(this::isValidBinaryLink).filter(isConfidentEnough).forEach(linkAB -> queue.add(new Step(Rule.INVERSION, now, linkAB)));

            // Collect Modus Ponens
            Stream.of(Type.INHERITANCE, Type.PREDICTIVE_IMPLICATION).flatMap(mem::links).filter(this::isValidBinaryLink).filter(isConfidentEnough).forEach(linkAB -> atom(linkAB.targets.getFirst()).ifPresent(premiseA -> {
                if (isConfidentEnough.test(premiseA)) queue.add(new Step(Rule.MODUS_PONENS, now, premiseA, linkAB));
            }));

            // TODO: Add potential Temporal Rule applications (e.g., Persistence)
            // TODO: Add potential HOL Rule applications (e.g., ForAll Instantiation)

            return queue;
        }

        /**
         * Executes the highest-priority infers from the queue.
         */
        private int exeTopInfers(PriorityQueue<Step> queue, Set<String> executedSignatures) {
            var infersMade = 0;
            var processedCount = 0;
            while (!queue.isEmpty() && processedCount < FORWARD_CHAINING_BATCH_SIZE) {
                var p = queue.poll();
                var signature = p.signature;

                if (!executedSignatures.contains(signature)) {
                    Optional<?> resultOpt = empty();
                    // Ensure premises still exist and are valid before executing
                    var pp = p.premise;
                    if (Arrays.stream(pp).allMatch(a -> _atom(a.id) != null)) {
                        // Add other rules here
                        resultOpt = switch (p.ruleType) {
                            case DEDUCTION -> deduce((Link) pp[0], (Link) pp[1]);
                            case INVERSION -> invert((Link) pp[0]);
                            case MODUS_PONENS -> modusPonens(pp[0], (Link) pp[1]);
                            default -> resultOpt;
                        };
                    }

                    executedSignatures.add(signature); // Mark as attempted (even if failed or premises vanished)

                    if (p.ruleType == Rule.DEDUCTION) // Avoid A->B->C vs C<-B<-A redundancy
                        executedSignatures.add(p.getSignatureSwapped());

                    if (resultOpt.isPresent()) {
                        infersMade++;
                        // System.out.printf("  FC Executed: %s -> %s (Priority: %.3f)%n", potential.ruleType, ((Atom)resultOpt.get()).id(), potential.priority);
                    }

                    processedCount++;
                }
            }
            return infersMade;
        }

        /**
         * Performs backward chaining query with unify.
         */
        public List<Answer> backward(Atom queryPattern, int maxDepth) {
            return backward(queryPattern, maxDepth, Bind.EMPTY_BIND, new HashSet<>());
        }

        private List<Answer> backward(Atom queryPattern, int depth, Bind bind, Set<String> visited) {
            // Apply current bind to the query pattern
            var q = unify.subst(queryPattern, bind);
            var visitedId = q.id + bind.hashCode(); // ID includes bind state

            if (depth <= 0 || !visited.add(visitedId)) return Collections.emptyList(); // Depth limit or cycle detected

            List<Answer> results = new ArrayList<>();
            var nextDepth = depth - 1;

            // 1. Direct Match in memory (after substitution)
            var Q = _atom(q.id);
            if (Q != null && Q.truth().conf() > TRUTH_MIN_CONFIDENCE_FOR_VALID_EVIDENCE) {
                // Check if the concrete match from memory unifies with the potentially var query
                unify.unify(q, Q, bind).ifPresent(finalBind -> results.add(new Answer(finalBind, Q)));
            }

            // 2. Rule-Based Derivation (Backward Application using unify)

            // Try Modus Ponens Backward: Query B, find A and A->B
            results.addAll(tryModusPonensBackward(q, nextDepth, bind, visited));

            // Try Deduction Backward: Query A->C, find A->B and B->C
            if (q instanceof Link queryLink && isValidBinaryLink(queryLink))
                results.addAll(tryDeductionBackward(queryLink, nextDepth, bind, visited));

            // Try Inversion Backward: Query B->A, find A->B
            if (q instanceof Link queryLink && isValidBinaryLink(queryLink))
                results.addAll(tryInversionBackward(queryLink, nextDepth, bind, visited));


            // Try ForAll Instantiation Backward: Query P(a), find ForAll(?X, P(?X)) rule
            results.addAll(tryForAllBackward(q, nextDepth, bind, visited));

            // TODO: Try Temporal Rules Backward (e.g., HoldsAt via Persistence/Initiates/Terminates)


            visited.remove(visitedId); // Backtrack
            return results; // Combine results from all successful paths
        }

        // --- Backward Rule Helpers ---

        private List<Answer> tryModusPonensBackward(Atom queryB, int depth, Bind bind, Set<String> visited) {
            List<Answer> results = new ArrayList<>();
            // Collect implications A->B, where B unifies with queryB
            Stream.of(Type.INHERITANCE, Type.PREDICTIVE_IMPLICATION).flatMap(mem::links).filter(this::isValidBinaryLink).forEach(ruleAB -> {
                var targets = ruleAB.targets;

                var potentialB = _atom(targets.get(1));
                if (potentialB == null) return;

                var potentialA = _atom(targets.get(0));
                if (potentialA == null) return;

                // Try to unify the rule's consequent (B) with the query (B)
                unify.unify(potentialB, queryB, bind).ifPresent(bindB -> {
                    // If unified, create subgoal to prove the premise (A) using bind from B unify
                    var subgoalA = unify.subst(potentialA, bindB);
                    for (var resA : backward(subgoalA, depth, bindB, visited)) {
                        // And confirm the rule A->B itself holds with sufficient confidence
                        var rulePattern = unify.subst(ruleAB, resA.bind);
                        for (var resAB : backward(rulePattern, depth, resA.bind, visited)) {
                            // If both subgoals met, calculate the inferred truth for B via MP
                            modusPonens(resA.inferred, (Link) resAB.inferred).ifPresent(inferredB -> {
                                // Final unify check with the original query and final bind
                                unify.unify(queryB, inferredB, resAB.bind).ifPresent(finalBind -> results.add(new Answer(finalBind, inferredB)));
                            });
                        }
                    }
                });
            });
            return results;
        }

        private List<Answer> tryDeductionBackward(Link queryAC, int depth, Bind bind, Set<String> visited) {
            List<Answer> results = new ArrayList<>();
            var aIdQuery = queryAC.targets.get(0);
            var cIdQuery = queryAC.targets.get(1);

            // Iterate through all links A->B of the same type
            mem.links(queryAC.type).filter(this::isValidBinaryLink).forEach(ruleAB -> {
                var targets = ruleAB.targets;
                var aIdRule = targets.get(0);
                var bIdRule = targets.get(1);

                // Unify query A with rule A
                unify.unify(aIdQuery, aIdRule, bind).ifPresent(bindA -> {
                    // Create subgoal B->C pattern, substituting bind from A unify
                    var subgoalBCPattern = new Link(queryAC.type, List.of(bIdRule, cIdQuery), Truth.UNKNOWN, null);
                    var subgoalBC = unify.subst(subgoalBCPattern, bindA);

                    // Recurse: Prove A->B (the rule itself)
                    for (var ab : backward(ruleAB, depth, bindA, visited)) {
                        // Recurse: Prove B->C
                        for (var bc : backward(subgoalBC, depth, ab.bind, visited)) {
                            // If subgoals met, perform deduction forward to get inferred A->C
                            deduce((Link) ab.inferred, (Link) bc.inferred).ifPresent(inferredAC -> {
                                // Final unify with original query and final bind
                                unify.unify(queryAC, inferredAC, bc.bind).ifPresent(finalBind -> results.add(new Answer(finalBind, inferredAC)));
                            });
                        }
                    }
                });
            });
            return results;
        }

        private List<Answer> tryInversionBackward(Link queryBA, int depth, Bind bind, Set<String> visited) {
            List<Answer> results = new ArrayList<>();
            var bIdQuery = queryBA.targets.get(0);
            var aIdQuery = queryBA.targets.get(1);

            // Collect rules A->B of the same type
            mem.links(queryBA.type).filter(this::isValidBinaryLink).forEach(ruleAB -> {
                var targets = ruleAB.targets;
                var aIdRule = targets.get(0);
                var bIdRule = targets.get(1);

                // Unify query B with rule B, and query A with rule A simultaneously
                unify.unify(bIdQuery, bIdRule, bind).flatMap(_b -> unify.unify(aIdQuery, aIdRule, _b)).ifPresent(initBind -> {
                    // If compatible, subgoal is to prove A->B
                    for (var ab : backward(ruleAB, depth, initBind, visited)) {
                        // If subgoal met, perform inversion forward
                        invert((Link) ab.inferred).ifPresent(inferredBA -> {
                            // Final unify check
                            unify.unify(queryBA, inferredBA, ab.bind).ifPresent(finalBind -> results.add(new Answer(finalBind, inferredBA)));
                        });
                    }
                });
            });
            return results;
        }

        private List<Answer> tryForAllBackward(Atom queryInstance, int depth, Bind bind, Set<String> visited) {
            List<Answer> results = new ArrayList<>();
            mem.links(Type.FOR_ALL).forEach(forAllLink -> {
                var targets = forAllLink.targets;
                var targetCount = targets.size();
                if (targetCount >= 2) { // Should be FOR_ALL(Var1, Var2..., Body)
                    //var varIds = targets.subList(0, targetCount - 1);
                    var bodyId = targets.getLast();

                    // Try to unify the query instance with the body pattern
                    var bodyPattern = _atom(bodyId);
                    if (bodyPattern != null) {
                        unify.unify(bodyPattern, queryInstance, bind).ifPresent(matchBind -> {
                            // If unify succeeds, the instance might hold if the ForAll rule itself holds.
                            // Check the ForAll rule's confidence recursively.
                            for (var resForAll : backward(forAllLink, depth, matchBind, visited)) {
                                // The instance is supported with the confidence of the ForAll rule.
                                // Substitute final bind into the original query instance.
                                var finalInstance = unify.subst(queryInstance, resForAll.bind);
                                var supportedInstance = finalInstance.withTruth(resForAll.inferred.truth());
                                results.add(new Answer(resForAll.bind, supportedInstance));
                            }
                        });
                    }
                }
            });
            return results;
        }


        // --- Planning ---

        /**
         * Attempts to plan a sequence of actions to achieve a goal state.
         */
        public Optional<List<Atom>> planToActionSequence(Atom goalPattern, int maxPlanDepth, int maxSearchDepth) {
            System.out.println("\n--- Infer: Planning ---");
            System.out.println("Goal Pattern: " + goalPattern.id);
            atom(goalPattern.id).ifPresent(g -> g.boost(IMPORTANCE_BOOST_ON_GOAL_FOCUS, mem.time.get()));

            // Use backward search from the goal
            return planRecursive(goalPattern, new LinkedList<>(), maxPlanDepth, maxSearchDepth, new HashSet<>());
        }

        private Optional<List<Atom>> planRecursive(Atom currentGoalPattern, LinkedList<Atom> currentPlan, int planDepthRemaining, int searchDepth, Set<String> visitedGoalPatterns) {
            var goalPatternId = currentGoalPattern.id + currentPlan.hashCode(); // State includes current plan context

            if (planDepthRemaining <= 0 || !visitedGoalPatterns.add(goalPatternId)) {
                //String indent = "  ".repeat(PLANNING_DEFAULT_MAX_PLAN_DEPTH - planDepthRemaining);
                //System.out.println(indent + "-> Stop (Depth/Cycle on " + currentGoalPattern.id() + ")");
                return empty();
            }

            // 1. Check if goal already holds (using backward chaining query)
            var goalResults = backward(currentGoalPattern, searchDepth);
            // Find the best result based on confidence and strength
            var bestResult = goalResults.stream().max(Comparator.comparingDouble(r -> r.inferred.truth().strength * r.inferred.truth().conf()));

            if (bestResult.isPresent() && bestResult.get().inferred.truth().strength > 0.7 && bestResult.get().inferred.truth().conf() > 0.5) {
                // System.out.println(indent + "-> Goal Holds: " + currentGoalPattern.id() + " (via " + bestResult.get().inferredAtom.id() + ")");
                return Optional.of(new ArrayList<>(currentPlan)); // Return copy of current plan (success)
            }

            // 2. Find actions that could achieve the goal
            var potentialSteps = findActionsAchieving(currentGoalPattern, searchDepth);
            // Sort potential steps (e.g., by confidence of effect, heuristic) - higher confidence first
            potentialSteps.sort(Comparator.comparingDouble(PotentialStep::confidence).reversed());

            // 3. Try each potential action
            for (var step : potentialSteps) {
                // System.out.println(indent + "-> Considering Action: " + step.action().id() + " (Conf: " + String.format("%.3f", step.confidence()) + ")");

                // Check if action is already in the current plan to avoid trivial loops
                if (currentPlan.stream().anyMatch(a -> a.id.equals(step.action.id))) continue;

                // Create new visited set for this branch to allow revisiting goals in different plan contexts
                var branchVisited = new HashSet<>(visitedGoalPatterns);

                // Recursively plan to satisfy preconditions
                var planWithPreconditionsOpt = satisfyPreconditions(step.preconditions, currentPlan, planDepthRemaining - 1, searchDepth, branchVisited);

                // If preconditions satisfied, add this action to the plan
                if (planWithPreconditionsOpt.isPresent()) {
                    var planFound = planWithPreconditionsOpt.get();
                    // Add the current action *after* its preconditions are met
                    planFound.addLast(step.action);
                    // System.out.println(indent + "--> Plan Step Found: " + step.action().id());
                    return Optional.of(planFound); // Found a complete plan for this branch
                }
            }

            visitedGoalPatterns.remove(goalPatternId); // Backtrack
            // System.out.println(indent + "-> No plan found from state for " + currentGoalPattern.id());
            return empty();
        }

        /**
         * Finds actions that might achieve the goalPattern based on memory rules.
         * TODO use 'searchDepth'
         */
        private List<PotentialStep> findActionsAchieving(Atom goalPattern, int searchDepth) {
            List<PotentialStep> steps = new ArrayList<>();

            // Find Predictive Implications: Sequence(Preconditions..., Action) => Goal
            mem.links(Type.PREDICTIVE_IMPLICATION)
                    // .filter(rule -> rule.targets().size() == 2) // Premise -> Consequence
                    .forEach(rule -> {
                        if (rule.targets.size() != 2) return; // Skip malformed rules
                        var premiseId = rule.targets.get(0);
                        var consequenceId = rule.targets.get(1);

                        var consequence = _atom(consequenceId);
                        if (consequence == null) return;

                        // Check if rule's consequence unifies with the current goal pattern
                        unify.unify(consequence, goalPattern, Bind.EMPTY_BIND).ifPresent(bind -> {
                            // If consequence matches, examine the premise
                            atom(premiseId).ifPresent(premiseAtom -> {
                                // Case 1: Premise is Sequence(State..., Action)
                                if (premiseAtom instanceof Link premiseLink && premiseLink.type == Type.SEQUENCE) {
                                    var actionOpt = premiseLink.targets.stream().map(mem::_atom).filter(Objects::nonNull).filter(this::isActionAtom) // Find the action within the sequence
                                            .findFirst();

                                    actionOpt.ifPresent(actionAtom -> {
                                        // Preconditions are the other elements in the sequence
                                        var preconditions = premiseLink.targets.stream().filter(id -> !id.equals(actionAtom.id)).map(mem::_atom).filter(Objects::nonNull).map(precond -> unify.subst(precond, bind)) // Apply bind from goal unify
                                                .toList();
                                        var confidence = rule.truth().conf() * premiseLink.truth().conf();
                                        steps.add(new PotentialStep(actionAtom, preconditions, confidence));
                                    });
                                }
                                // Case 2: Premise is directly an Action (implies no preconditions in this rule)
                                else if (isActionAtom(premiseAtom)) {
                                    steps.add(new PotentialStep(premiseAtom, Collections.emptyList(), rule.truth().conf()));
                                }
                                // Case 3: Premise requires further backward chaining to find an action (more complex, not fully implemented here)
                                // else { /* Could try backward chaining on the premise to find a path involving an action */ }
                            });
                        });
                    });

            // TODO: Add checks for INITIATES links (Event Calculus style)
            // If Goal is HoldsAt(Fluent), look for Action -> Initiates(Fluent)

            return steps;
        }


        /**
         * Helper to recursively satisfy a list of preconditions for planning.
         */
        private Optional<LinkedList<Atom>> satisfyPreconditions(List<Atom> preconditions, LinkedList<Atom> currentPlan, int planDepthRemaining, int searchDepth, Set<String> visitedGoalPatterns) {
            if (preconditions.isEmpty()) {
                return Optional.of(new LinkedList<>(currentPlan)); // Base case: success, return copy
            }

            var nextPrecondition = preconditions.getFirst();
            var remainingPreconditions = preconditions.subList(1, preconditions.size());

            // Recursively plan to achieve the next precondition
            // Pass a *copy* of the current plan, as this branch might fail
            var planForPreconditionOpt = planRecursive(nextPrecondition, new LinkedList<>(currentPlan), planDepthRemaining, searchDepth, visitedGoalPatterns);

            if (planForPreconditionOpt.isPresent()) {
                // Precondition achieved. The returned list is the *complete* plan up to this point.
                // Now, try to satisfy the *remaining* preconditions starting from the state achieved by this sub-plan.
                var planAfterPrecondition = new LinkedList<>(planForPreconditionOpt.get());

                // Check depth again after sub-plan potentially used steps
                var depthRemainingAfterSubPlan = planDepthRemaining - (planAfterPrecondition.size() - currentPlan.size());
                if (depthRemainingAfterSubPlan < 0) return empty(); // Sub-plan exceeded depth limit

                return satisfyPreconditions(remainingPreconditions, planAfterPrecondition, depthRemainingAfterSubPlan, searchDepth, visitedGoalPatterns);
            } else {
                // Failed to satisfy this precondition
                return empty();
            }
        }

        // --- Helpers ---

        private boolean isValidBinaryLink(Atom atom) {
            return atom instanceof Link link && link.targets.size() == 2 && (link.type == Type.INHERITANCE || link.type == Type.PREDICTIVE_IMPLICATION);
        }

        private boolean isActionAtom(Atom atom) {
            // Define convention: Action atoms are Nodes with names starting "Action:"
            return atom instanceof Node node && node.name.startsWith("Action:");
            // Alternative: check for specific link type like EXECUTION if used
        }

        private double calculateInferredImportance(Atom... premises) {
            long now = mem.time.get();
            var premiseImportanceProduct = Arrays.stream(premises).mapToDouble(p -> p.getCurrentImportance(now) * p.truth().conf()).reduce(1, (a, b) -> a * b); // Combine importance, weighted by confidence
            return IMPORTANCE_BOOST_ON_ACCESS + premiseImportanceProduct * IMPORTANCE_BOOST_INFERRED_FACTOR;
        }

        // --- Helper Records/Enums for Infer Control ---
        private enum Rule {DEDUCTION, INVERSION, MODUS_PONENS, FOR_ALL_INSTANTIATION, TEMPORAL_PERSISTENCE}

        /**
         * Represents a potential infer step for prioritization in forward chaining.
         */
        private static class Step {
            final Rule ruleType;
            final Atom[] premise;
            final double priority; // Combined importance * confidence of premises
            final String signature; // Unique ID for visited set

            Step(Rule type, long now, Atom... premise) {
                this.ruleType = type;
                this.premise = premise;
                // Priority based on product of premise importances weighted by confidence
                this.priority = Arrays.stream(premise).mapToDouble(p -> p.getCurrentImportance(now) * p.truth().conf()).reduce(1, (a, b) -> a * b);
                this.signature = ruleType + ":" + Arrays.stream(premise).map(atom -> atom.id).sorted().collect(Collectors.joining("|"));
            }

            String getSignatureSwapped() { // For symmetric rules like Deduction A->B->C vs C<-B<-A
                return premise.length == 2 ? ruleType + ":" + Stream.of(premise[1].id, premise[0].id).sorted().collect(Collectors.joining("|")) : signature;
            }
        }

        /**
         * Represents a potential step in a plan.
         */
        private record PotentialStep(Atom action, List<Atom> preconditions, double confidence) {
        }
    }

    /**
     * Abstract base class for all elements in the Knowledge Base (Nodes and Links).
     * Manages identity, truth, importance, and access time. Uses VarHandles for
     * efficient, thread-safe updates to volatile importance fields.
     */
    public abstract static sealed class Atom permits Node, Link {
        private static final VarHandle STI, LTI, LAST_ACCESS_TIME;

        static {
            try {
                var l = MethodHandles.lookup();
                STI = l.findVarHandle(Atom.class, "sti", double.class);
                LTI = l.findVarHandle(Atom.class, "lti", double.class);
                LAST_ACCESS_TIME = l.findVarHandle(Atom.class, "lastAccessTime", long.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        public final String id;

        private volatile Truth truth; // Mutable truth

        // Volatile fields managed by VarHandles for atomic updates
        @SuppressWarnings("unused")
        private volatile double sti, lti;
        @SuppressWarnings("unused")
        private volatile long lastAccessTime;


        protected Atom(String id, Truth truth) {
            this.id = Objects.requireNonNull(id);
            this.truth = Objects.requireNonNull(truth);
            // Importance initialized later via initializeImportance or implicitly
        }

        public final Truth truth() {
            return truth;
        }

        // Internal setter used by memory revision
        final void truth(Truth truth) {
            this.truth = truth;
        }

        private void sti(double x) {
            STI.setVolatile(this, x);
        }

        public void lti(double x) {
            LTI.setVolatile(this, x);
        }

        public double lti() {
            return (double) LTI.getVolatile(this);
        }

        public double sti() {
            return (double) STI.getVolatile(this);
        }

        /**
         * priority initialization
         */
        final void pri(double sti, long now) {
            var clampedSTI = unitize(sti);
            sti(clampedSTI);
            lti(clampedSTI * IMPORTANCE_INITIAL_LTI_FACTOR); // LTI starts lower
            updateAccessTime(now);
        }

        /**
         * Atomically updates importance based on a boost event and decays existing STI/LTI slightly.
         */
        final void boost(double boost, long now) {
            boost = Math.max(0, boost); // Ensure non-negative boost

            // Atomically update STI: newSTI = min(1.0, currentSTI * decay + boost)
            var newSTI = updateImportance(sti(), IMPORTANCE_STI_DECAY_RATE, boost, STI);

            updateImportance(lti(), IMPORTANCE_LTI_DECAY_RATE, newSTI * IMPORTANCE_STI_TO_LTI_RATE, LTI);

            updateAccessTime(now); // Also update access time on importance boost
        }

        private double updateImportance(double value, double decayRate, double add, VarHandle h) {
            // Atomically update LTI: newLTI = min(1.0, currentLTI * decay + newSTI * learning_rate)
            double current, next;
            do {
                current = value;
                next = Math.min(1, current * (1 - decayRate * 0.1) + add);
            } while (!h.compareAndSet(this, current, next));
            return next;
        }

        /**
         * Atomically decays importance (called periodically by maintenance task).
         * TODO consider param now?
         */
        final void decayImportance(long now) {
            var currentSTI = sti();
            var decayedSTI = currentSTI * (1 - IMPORTANCE_STI_DECAY_RATE);
            sti(decayedSTI); // Direct set is fine here if only maintenance thread calls decay

            var currentLTI = lti();
            // LTI also learns from the decayed STI value
            var decayedLTI = currentLTI * (1 - IMPORTANCE_LTI_DECAY_RATE) + decayedSTI * IMPORTANCE_STI_TO_LTI_RATE * 0.1; // Slow learn during decay
            lti(Math.min(1, decayedLTI));
        }

        /**
         * Updates the last access time atomically.
         */
        void updateAccessTime(long time) {
            LAST_ACCESS_TIME.setVolatile(this, time);
        }

        /**
         * Calculates a combined importance score, factoring STI, LTI, recency, and confidence.
         */
        public final double getCurrentImportance(long now) {
            var lastAccess = lastAccessTime();

            double timeSinceAccess = Math.max(0, now - lastAccess);
            // Recency factor: decays exponentially, influences mostly STI contribution
            var recencyFactor = Math.exp(-timeSinceAccess / (FORGETTING_CHECK_INTERVAL_MS * 3.0)); // Decay over ~3 cycles

            // Combine STI (weighted by recency) and LTI
            var combinedImportance = sti() * recencyFactor * 0.5 + lti() * 0.5;
            // Modulate by confidence - less confident atoms are less important overall
            return combinedImportance * truth.conf();
        }

        public final long lastAccessTime() {
            return (long) LAST_ACCESS_TIME.getVolatile(this);
        }

        /**
         * Abstract method for creating a copy with a different Truth.
         */
        public abstract Atom withTruth(Truth newTruth);

        @Override
        public final boolean equals(Object o) {
            if (this == o) return true;
            return o != null && getClass() == o.getClass() && id.equals(((Atom) o).id);
        }

        @Override
        public final int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            return String.format("%s %s", id, truth);
            // Verbose: String.format("%s %s <S=%.2f L=%.2f>", id, truth, (double)sti(), (double)lti());
        }
    }

    /**
     * Node Atom: Represents concepts, entities, predicates, actions, fluents, etc.
     */
    public static sealed class Node extends Atom {
        public final String name; // The human-readable name

        public Node(String name, Truth truth) {
            super(id(name), truth);
            this.name = name;
        }

        /**
         * Use <> for clarity
         */
        public static String id(String name) {
            return "N<" + name + ">";
        }

        @Override
        public Atom withTruth(Truth newTruth) {
            var newNode = new Node(this.name, newTruth);
            // Copy importance state - assumes temporary copy, memory manages learned atom's state
            newNode.pri(sti(), lastAccessTime());
            newNode.lti(lti());
            return newNode;
        }

        @Override
        public String toString() {
            return String.format("%s %s", name, truth());
        } // More readable default
    }

    /**
     * Variable Node: Special Node type used in patterns and rules (HOL).
     * truth=unknown, ID includes prefix
     * TODO consider '$var' syntax
     */
    public static final class Var extends Node {
        public static final String EMPTY = varID("");

        public Var(String name) {
            super(VAR_PREFIX + name, Truth.UNKNOWN); // Importance handled by memory (usually protected)
        }

        public static String varID(String name) {
            return "V<" + VAR_PREFIX + name + ">";
        }

        @Override
        public Atom withTruth(Truth newTruth) {
            // Creating a copy of a var doesn't make sense with different truth
            return new Var(name.substring(VAR_PREFIX.length()));
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Link Atom: Represents relationships, rules, sequences, logical connectives between Atoms.
     */
    public static final class Link extends Atom {
        public final Cog.Type type;
        public final List<String> targets; // Immutable list of target Atom IDs
        private Time time; // Optional, mutable time specification

        public Link(Cog.Type type, List<String> targets, Truth truth, Time time) {
            super(id(type, targets), truth);
            this.type = Objects.requireNonNull(type);
            this.targets = List.copyOf(targets); // Ensure immutable list TODO avoid unnecessary copies
            this.time = time;
        }

        public static String id(Cog.Type type, Collection<String> targets) {
            // Sort targets for order-independent types to ensure canonical ID
            var idTargets = type.commutative && targets.size() > 1 ? targets.stream().sorted().toList() : targets;
            // Simple, readable format: TYPE(Target1,Target2,...)
            return type + "(" + String.join(",", idTargets) + ")";
        }

        @Nullable
        public Time time() {
            return time;
        }

        // Internal setter used by memory revision
        void setTime(Time time) {
            this.time = time;
        }

        @Override
        public Atom withTruth(Truth newTruth) {
            //TODO if newTruth is equal, do we need a new instance?
            var l = new Link(type, targets, newTruth, time);
            l.pri(sti(), lastAccessTime()); // Copy importance state
            l.lti(lti());
            return l;
        }

        @Override
        public String toString() {
            var timeStr = (time != null) ? " " + time : "";
            return String.format("%s %s%s", super.toString(), timeStr, ""); // Let super handle ID + truth
            // Verbose: String.format("%s %s%s %s", type, targets, timeStr, truth());
        }

    }

    /**
     * Immutable record representing probabilistic truth value (Strength and Confidence).
     * Strength: Probability or degree of truth (0.0 to 1.0).
     * Count: Amount of evidence supporting this value.
     */
    public record Truth(double strength, double evi) {
        public static final Truth DEFAULT = Truth.of(0.5, 0), // Default prior: ignorance
                TRUE = Truth.of(1, 10), // Strong positive evidence
                FALSE = Truth.of(0, 10), // Strong negative evidence
                UNKNOWN = Truth.of(0.5, 0.1); // Explicitly unknown/low evidence

        /**
         * Factory method
         */
        public static Truth of(double strength, double count) {
            return new Truth(unitize(strength), Math.max(0, count));
        }

        /**
         * Confidence: Derived measure of truth based on count (0.0 to 1.0).
         * Calculates confidence: count / (count + sensitivity). Higher count -> higher confidence.
         */
        public double conf() {
            return evi / (evi + TRUTH_DEFAULT_SENSITIVITY);
        }

        /**
         * Merges this Truth with another, producing a combined value.
         * This implements Bayesian revision: weighted average of strengths based on counts.
         */
        public Truth merge(Truth other) {
            if (this == other) return this;

            var oc = other.evi;
            if (oc == 0) return this;
            var tc = this.evi;
            if (tc == 0) return other;

            var totalCount = tc + oc;
            // Weighted average strength: (s1*c1 + s2*c2) / (c1 + c2)
            var mergedStrength = (this.strength * tc + other.strength * oc) / totalCount;

            return Truth.of(mergedStrength, totalCount);
        }

        @Override
        public String toString() {
            return String.format("<s:%.3f, c:%.2f, conf:%.3f>", strength, evi, conf());
        }
    }

    /**
     * Immutable record representing temporal scope (point, interval, or relative range).
     * Times are typically based on the engine's logical time clock.
     */
    public record Time(long start, long end, boolean rel) {
        // Common instances
        public static final Time ETERNAL = new Time(Long.MIN_VALUE, Long.MAX_VALUE, false); // Always holds
        public static final Time NOW = new Time(0, 0, true); // Relative, zero range/delay


        /**
         * Creates a time point (absolute or relative).
         */
        public static Time at(long timePoint, boolean isRelative) {
            return new Time(timePoint, timePoint, isRelative);
        }

        public static Time at(long timePoint) {
            return new Time(timePoint, timePoint, false);
        }

        public static Time between(long startTime, long endTime, boolean isRelative) {
            if (!isRelative && endTime < startTime)
                throw new IllegalArgumentException("Absolute end time must be >= start time");
            return new Time(startTime, endTime, isRelative);
        }

        public static Time between(long startTime, long endTime) {
            return between(startTime, endTime, false);
        }

        public static Time range(long range) {
            return new Time(0, Math.max(0, range), true);
        }

        /**
         * Composes two TimeSpecs, typically for sequence delays. Adds ranges.
         */
        public static Time compose(Time a, Time b) {
            if (a == null) return b;
            if (b == null) return a;
            if (!a.rel || !b.rel) {
                // Cannot easily compose absolute times without context, return wider interval?
                return Time.between(Math.min(a.start, b.start), Math.max(a.end, b.end), false); // Simplistic merge
            }
            // Add relative ranges
            return Time.range(a.range() + b.range());
        }

        public static Time merge(Link a, Link b) {
            return Time.merge(a.time(), b.time());
        }

        /**
         * Merges two time specs, typically representing combined evidence. Returns the encompassing interval.
         */
        public static Time merge(Time a, Time b) {
            if (a == null) return b;
            if (b == null || b == a) return a;
            // Simple merge: take the union of the time intervals, assume absolute if either is
            var mergedRelative = a.rel && b.rel;
            var mergedStart = Math.min(a.start, b.start);
            var mergedEnd = Math.max(a.end, b.end);
            return Time.between(mergedStart, mergedEnd, mergedRelative);
        }

        public boolean isPoint() {
            return start == end;
        }

        public long range() {
            return end - start;
        }

        /**
         * Inverts a relative range (e.g., for backward temporal reasoning).
         */
        public Time inverse() {
            return rel ? Time.range(-this.range()) : this; // Cannot invert absolute time
        }

        @Override
        public String toString() {
            var prefix = rel ? "Rel" : "Abs";
            return isPoint() ? String.format("T:%s@%d", prefix, start) : String.format("T:%s[%d,%d]", prefix, start, end);
        }
    }

    /**
     * Results from backward chaining queries
     */
    public record Answer(Bind bind, Atom inferred) {
    }


    /**
     * Variable mapping, immutable
     */
    public record Bind(Map<String, String> map) {
        public static final Bind EMPTY_BIND = new Bind(Collections.emptyMap());

        public Bind {
            map = Map.copyOf(map); // Ensure immutability //TODO avoid duplication
        }

        public String getOrElse(String varId, String orElse) {
            var v = get(varId);
            return v == null ? orElse : v;
        }

        @Nullable
        public String get(String varId) {
            return map.get(varId);
        }

        /**
         * Creates new bind extended with one more mapping. Returns empty Optional if inconsistent.
         */
        public Optional<Bind> put(String varId, String valueId) {
            var mk = get(varId);
            if (mk != null) { // var already bound
                return mk.equals(valueId) ? Optional.of(this) : empty(); // Consistent?
            }
            if (map.containsValue(varId)) { // Trying to bind X to Y where Y is already bound to Z
                // Check for cycles or inconsistencies: If valueId is a var already bound to something else.
                if (valueId.startsWith(Var.EMPTY)) {
                    var mv = get(valueId);
                    if (mv != null && !mv.equals(varId)) return empty();
                }
                // Avoid occurs check for simplicity here, assume valid structure
            }

            var newMap = new HashMap<>(map);
            newMap.put(varId, valueId);
            return Optional.of(new Bind(newMap));
        }

        /**
         * Merges two sets of bind Returns empty Optional if inconsistent.
         */
        public Optional<Bind> merge(Bind other) {
            Map<String, String> mergedMap = new HashMap<>(this.map);
            for (var entry : other.map.entrySet()) {
                var varId = entry.getKey();
                var valueId = entry.getValue();
                if (mergedMap.containsKey(varId)) {
                    if (!mergedMap.get(varId).equals(valueId)) {
                        return empty(); // Inconsistent bind
                    }
                } else {
                    mergedMap.put(varId, valueId);
                }
            }
            // Check for transitive inconsistencies (e.g., X=Y in this, Y=Z in other, but X=A in merged) - simple check
            for (var entry : mergedMap.entrySet()) {
                var currentVal = entry.getValue();
                if (mergedMap.containsKey(currentVal) && !mergedMap.get(currentVal).equals(entry.getKey())) {
                    // Check requires deeper graph traversal for full consistency, simple check here
                    // Example: X=Y, Y=X is okay. X=Y, Y=Z, Z=X okay. X=Y, Y=Z, X=A is not.
                    // For now, allow basic merge.
                }
            }

            return Optional.of(new Bind(mergedMap));
        }

        @Override
        public String toString() {
            return map.toString();
        }

        /**
         * follow bind chain for an ID (might be var or concrete)
         */
        private String follow(String id) {
            var at = id;
            var next = get(at);
            if (next == null) return at;
            var visited = new HashSet<>();
            while (visited.add(at)) {
                if (next.startsWith(Var.EMPTY)) {
                    at = next;
                    next = get(at);
                    if (next == null) break;
                } else {
                    return next; // Bound to concrete ID
                }
            }
            return at; // Unbound or cycle detected, return last ID found
        }

    }

    /**
     * Performs unify between Atom patterns.
     */
    public static class Unify {
        private final Memory mem; // Needed to resolve IDs to Atoms if necessary

        public Unify(Memory mem) {
            this.mem = mem;
        }

        /**
         * Unifies two Atoms, returning updated bind if successful.
         */
        public Optional<Bind> unify(Atom pattern, Atom instance, Bind init) {
            // Dereference vars in both pattern and instance based on initial bind
            var p = subst(pattern, init);
            var i = subst(instance, init);

            if (p instanceof Var varP) return init.put(varP.id, i.id);

            if (i instanceof Var varI) return init.put(varI.id, p.id);


            // Must be same Atom type (Node or Link)
            if (p.getClass() != i.getClass()) return empty();

            if (p instanceof Node nodeP && i instanceof Node nodeI) {
                // Nodes unify only if they are identical
                return nodeP.id.equals(nodeI.id) ? Optional.of(init) : empty();
            }

            if (p instanceof Link linkP && i instanceof Link linkI) {
                // Links unify if type matches and all targets unify recursively
                var numTargets = linkP.targets.size();
                if (linkP.type != linkI.type || numTargets != linkI.targets.size()) return empty();

                var currentBind = init;
                for (var j = 0; j < numTargets; j++) {
                    var targetPId = linkP.targets.get(j);
                    var targetIId = linkI.targets.get(j);

                    // Recursively unify target IDs
                    var result = unify(targetPId, targetIId, currentBind);
                    if (result.isEmpty()) return empty(); // unify failed for a target

                    currentBind = result.get();
                }
                return Optional.of(currentBind); // All targets unified successfully
            }

            return empty(); // Should not happen if Atoms are Nodes or Links
        }

        /**
         * Unifies two Atom IDs, resolving them to Atoms if needed.
         */
        public Optional<Bind> unify(String patternId, String instanceId, Bind bind) {
            var pId = bind.getOrElse(patternId, patternId);
            var iId = bind.getOrElse(instanceId, instanceId);

            if (pId.equals(iId)) return Optional.of(bind);

            if (pId.startsWith(Var.EMPTY)) return bind.put(pId, iId);

            if (iId.startsWith(Var.EMPTY)) return bind.put(iId, pId);

            // If neither are vars and they are not equal, they don't unify directly by ID.
            // However, if they represent complex structures (Links), we need to unify their structure.
            var patternAtomOpt = mem._atom(pId);
            if (patternAtomOpt != null) {
                var instanceAtomOpt = mem._atom(iId);
                if (instanceAtomOpt != null)
                    return unify(patternAtomOpt, instanceAtomOpt, bind); // Delegate to full Atom unify
            }

            return empty(); // If atoms cannot be retrieved, assume non-match if IDs differ
        }


        /**
         * Applies bind to substitute vars in an Atom pattern.
         */
        public Atom subst(Atom pattern, Bind bind) {
            if (bind.map.isEmpty()) return pattern; // No substitutions needed

            if (pattern instanceof Var var) {
                // Follow the bind chain until a non-var or unbound var is found
                var current = var.id;
                var bound = bind.get(current);
                var visited = new HashSet<>();
                while (bound != null && visited.add(current)) {
                    if (bound.startsWith(Var.EMPTY)) {
                        current = bound;
                        bound = bind.get(current);
                    } else {
                        // Bound to a concrete value ID, retrieve the corresponding Atom
                        return mem.atomOrElse(bound, pattern); // Return original if bound value doesn't exist
                    }
                }
                // If loop detected or unbound, return the last var in the chain or original
                return mem.atomOrElse(current, pattern);
            }

            if (pattern instanceof Link link) {
                var changed = false;
                var originalTargets = link.targets;
                List<String> newTargets = new ArrayList<>(originalTargets.size());
                for (var targetId : originalTargets) {
                    // Recursively substitute in targets
                    var targetPattern = mem._atom(targetId); // Get Atom for target ID
                    String newTargetId;
                    if (targetPattern != null) {
                        var substitutedTarget = subst(targetPattern, bind);
                        newTargetId = substitutedTarget.id;
                        if (!targetId.equals(newTargetId)) changed = true;
                    } else {
                        // If target doesn't exist, maybe it's a var? Check bind directly.
                        newTargetId = bind.follow(targetId);
                        if (!targetId.equals(newTargetId)) changed = true;
                    }
                    newTargets.add(newTargetId);
                }

                if (changed) {
                    // Create a new Link with substituted targets, keeping original truth/time/importance
                    var newLink = new Link(link.type, newTargets, link.truth(), link.time());
                    newLink.pri(link.sti(), link.lastAccessTime());
                    newLink.lti(link.lti());
                    return newLink;
                } else {
                    return link; // No change
                }
            }

            return pattern; // Node that is not a var - no substitution possible
        }

    }

    /**
     * Result of perception: a collection of Atoms representing observed facts/fluents.
     */
    public record Percept(Collection<Atom> perceived) {
    }

    /**
     * Result of executing an action: the new perceived state and any reward received.
     */
    public record Act(Collection<Atom> newState, double reward) {
    }

    // --- BasicGridWorld Example Implementation ---
    static class BasicGridWorld implements Game {
        private final Cog cle;
        private final int worldSize;
        private final int goalX, goalY;
        private final int maxSteps = 25;
        // Pre-created Atom schemas for efficiency
        private final Node agentNode;
        private final Node moveN, moveS, moveE, moveW;
        private final Node atLocationPred;
        private final ConcurrentMap<String, Node> positionNodes = new ConcurrentHashMap<>(); // Cache pos nodes
        private int agentX = 0, agentY = 0;
        private int steps = 0;

        BasicGridWorld(Cog cle, int size, int goalX, int goalY) {
            this.cle = cle;
            this.worldSize = size;
            this.goalX = goalX;
            this.goalY = goalY;

            // Create core nodes used by the environment/agent representation
            this.agentNode = cle.node("Self");
            this.atLocationPred = cle.node("Predicate:AtLocation");
            this.moveN = cle.node("Action:MoveNorth");
            this.moveS = cle.node("Action:MoveSouth");
            this.moveE = cle.node("Action:MoveEast");
            this.moveW = cle.node("Action:MoveWest");
        }

        private Node getPositionNode(int x, int y) {
            return positionNodes.computeIfAbsent(String.format("Pos_%d_%d", x, y), cle::node);
        }

        @Override
        public Percept perceive() {
            var currentPosNode = getPositionNode(agentX, agentY);
            // State representation: HoldsAt(Evaluation(AtLocation, Self, Pos_X_Y), Time)
            // 1. Create the inner Evaluation link: Evaluation(AtLocation, Self, Pos_X_Y)
            var atLocationEval = cle.learn(Type.EVALUATION, Truth.TRUE, 0.8, atLocationPred, agentNode, currentPosNode);
            // 2. Create the outer HoldsAt link: HoldsAt(InnerLink, Time)
            var agentAtLink = cle.learn(Type.HOLDS_AT, Truth.TRUE, 0.9, Time.at(cle.iteration()), atLocationEval);

            // We return the HoldsAt link as the primary perception atom
            return new Percept(List.of(agentAtLink));
        }

        @Override
        public List<Atom> actions() {
            List<Atom> actions = new ArrayList<>(4);
            // Return the Node schemas representing actions
            if (agentY < worldSize - 1) actions.add(moveN);
            if (agentY > 0) actions.add(moveS);
            if (agentX < worldSize - 1) actions.add(moveE);
            if (agentX > 0) actions.add(moveW);
            return actions;
        }

        @Override
        public Act exe(Atom actionSchema) {
            steps++;
            var reward = -0.05; // Small cost for moving

            String actionName;
            actionName = actionSchema instanceof Node node ? node.name : "UnknownAction";

            switch (actionName) {
                case "Action:MoveNorth":
                    if (agentY < worldSize - 1) agentY++;
                    else reward -= 0.2;
                    break; // Penalty for bump
                case "Action:MoveSouth":
                    if (agentY > 0) agentY--;
                    else reward -= 0.2;
                    break;
                case "Action:MoveEast":
                    if (agentX < worldSize - 1) agentX++;
                    else reward -= 0.2;
                    break;
                case "Action:MoveWest":
                    if (agentX > 0) agentX--;
                    else reward -= 0.2;
                    break;
                default:
                    System.err.println("WARN: Unknown action executed in GridWorld: " + actionSchema.id);
                    reward -= 1.0;
                    break;
            }

            if (agentX == goalX && agentY == goalY) {
                reward = 1.0; // Goal reward
                System.out.println("GridWorld: Agent reached the goal!");
            } else if (!running() && !(agentX == goalX && agentY == goalY)) {
                reward = -1.0; // Penalty for running out of time
                System.out.println("GridWorld: Agent ran out of steps.");
            }

            // Return new state perception and reward
            return new Act(perceive().perceived, reward);
        }

        @Override
        public boolean running() {
            return steps < maxSteps && !(agentX == goalX && agentY == goalY);
        }
    }


    // === Nested Static Class: AgentController                           ===

    public class Agent {
        private final RandomGenerator random = new Random();
        private Atom lastAction = null, previousState = null, currentGoal = null;

        /**
         * Runs the main agent perceive-reason-act-learn loop.
         */
        public void run(Game env, Atom goal, int maxCycles) {
            System.out.println("\n--- Agent: Starting Run ---");
            this.currentGoal = goal;

            mem._atom(goal.id).boost(IMPORTANCE_BOOST_ON_GOAL_FOCUS, iteration());

            System.out.println("Initial Goal: " + currentGoal.id + " " + currentGoal.truth());

            // Initial perception
            var initialPerception = env.perceive();
            previousState = perceiveAndLearnState(initialPerception.perceived);

            for (var cycle = 0; cycle < maxCycles && env.running(); cycle++) {
                var now = iteration(); // Use shared logical time
                System.out.printf("\n--- Agent Cycle %d (Time: %d) ---%n", cycle + 1, now);
                if (previousState != null) {
                    System.out.println("Current State Atom: " + previousState.id);
                    // Optional: Display current state details
                    // mem.getLinksWithTarget(previousStateAtom.id())
                    //    .filter(l -> l.type() == Link.Type.MEMBER) // Assuming state composed of MEMBER links
                    //   .forEach(m -> System.out.println("  - " + m.targets().get(0)));
                } else {
                    System.out.println("Current State Atom: Unknown");
                }


                // 1. Reason (Optional: Focused Forward Chaining based on current state/goal)
                // infer.forwardChain(1); // Limited, broad forward chaining

                // 2. Plan & Select Action
                var selectedActionOpt = selectAction(env);

                if (selectedActionOpt.isEmpty()) {
                    System.out.println("Agent: No suitable action found or planned. Idling.");
                    lastAction = null; // No action taken
                    // Optional: decay importance or take other idle action?
                } else {
                    lastAction = selectedActionOpt.get();
                    System.out.println("Agent: Selected Action: " + lastAction.id);

                    // 3. Execute Action
                    var actionResult = env.exe(lastAction);

                    // 4. Perceive Outcome
                    var currentState = perceiveAndLearnState(actionResult.newState);

                    // 5. Learn from Experience
                    learnFromExperience(currentState, actionResult.reward, now);

                    // Update state for next loop
                    previousState = currentState;
                }

                // 6. Goal Check
                if (goalAchieved()) {
                    System.out.println("*** Agent: Goal Achieved! (" + currentGoal.id + ") ***");
                    break; // Terminate loop on goal achievement
                }

                // Optional delay
                // try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            System.out.println("--- Agent: Run Finished ---");
        }

        /**
         * Processes raw perception, learns atoms, and creates/updates a composite state atom.
         */
        private Atom perceiveAndLearnState(Collection<Atom> perceived) {
            final var now = iteration();
            List<String> presentFluentIds = Collections.synchronizedList(new ArrayList<>()); // Thread-safe list

            // Learn individual perceived atoms with high confidence and boost importance
            perceived.parallelStream().forEach(pAtom -> {
                // Assign high confidence based on perception count
                var perceivedTruth = Truth.of(pAtom.truth().strength, AGENT_DEFAULT_PERCEPTION_COUNT);
                var currentPercept = pAtom.withTruth(perceivedTruth);

                // Add time information if it's a temporal predicate/fluent
                if (currentPercept instanceof Link link) {
                    var t = link.type;
                    if (t == Type.HOLDS_AT || t == Type.EVALUATION) link.setTime(Time.at(now));
                }

                // Learn the atom, boosting its importance significantly
                var learned = mem.learn(currentPercept);
                learned.boost(IMPORTANCE_BOOST_ON_PERCEPTION, now);

                // If atom represents a "true" fluent/state, add its ID for composite state
                if (learned.truth().strength > 0.5 && learned.truth().conf() > 0.5) {
                    // Filter for relevant state atoms (e.g., HOLDS_AT links, specific EVALUATION links)
                    if (learned instanceof Link link) {
                        if (link.type == Type.HOLDS_AT) {
                            presentFluentIds.add(learned.id);
                        }
                    } else {
                        if (learned instanceof Node node && node.name.startsWith("State:")) { // Or just nodes representing state
                            presentFluentIds.add(learned.id);
                        }
                    }
                    // Add other conditions as needed based on state representation conventions
                }
            });

            // Create a composite state representation (e.g., StateNode linked to its fluent members)
            if (presentFluentIds.isEmpty()) {
                var emptyState = mem.node("State:Empty", Truth.TRUE, 0.8);
                emptyState.boost(IMPORTANCE_BOOST_ON_PERCEPTION, now);
                return emptyState;
            }

            Collections.sort(presentFluentIds); // Ensure canonical representation
            // Simple ID based on sorted fluent IDs hash
            var stateName = "State:" + Math.abs(String.join("|", presentFluentIds).hashCode());
            var stateNode = mem.node(stateName, Truth.TRUE, 0.9); // High STI for current state
            stateNode.boost(IMPORTANCE_BOOST_ON_PERCEPTION, now);

            // Optional: Explicitly link state node to its members for better reasoning
            // for (String fluentId : presentFluentIds) {
            //     mem.learnLinkByIds(Link.Type.MEMBER, Truth.TRUE, 0.7, stateNode.id(), fluentId);
            // }

            return stateNode;
        }

        /**
         * Selects an action using planning, reactive evaluation, or exploration.
         */
        private Optional<Atom> selectAction(Game env) {
            var availableActions = env.actions();
            if (availableActions.isEmpty()) return empty();

            // 1. Try Planning towards the current goal
            var planOpt = infer.planToActionSequence(currentGoal, PLANNING_DEFAULT_MAX_PLAN_DEPTH, INFER_DEFAULT_MAX_DEPTH);
            if (planOpt.isPresent() && !planOpt.get().isEmpty()) {
                var firstActionInPlan = planOpt.get().getFirst(); // Get the first action recommended by the plan
                // Check if the planned action is actually available now
                if (availableActions.stream().anyMatch(a -> a.id.equals(firstActionInPlan.id))) {
                    System.out.println("Agent: Selecting action from plan: " + firstActionInPlan.id);
                    return Optional.of(firstActionInPlan);
                } else {
                    System.out.println("Agent: Planned action " + firstActionInPlan.id + " not available. Evaluating alternatives.");
                }
            } else {
                System.out.println("Agent: Planning failed or yielded empty plan.");
            }

            // 2. Fallback: Reactive Selection based on learned utility ("GoodAction" or predicted reward)
            System.out.println("Agent: Falling back to reactive selection...");
            Map<Atom, Double> actionUtilities = new ConcurrentHashMap<>();
            var goodActionNode = mem.node("GoodAction"); // Target node for utility
            if (goodActionNode != null) {

                availableActions.parallelStream().forEach(action -> {
                    // Query the estimated utility: Inheritance(Action, GoodAction)
                    var utilityQuery = new Link(Type.INHERITANCE, List.of(action.id, goodActionNode.id), Truth.UNKNOWN, null);
                    var results = query(utilityQuery, 2); // Shallow search for utility

                    var utility = results.stream().mapToDouble(r -> r.inferred.truth().strength * r.inferred.truth().conf()) // Combine strength & confidence
                            .max().orElse(0); // Max utility found

                    // Alternative/Combined: Predict immediate reward: PredictiveImplication(Action, Reward)
                    // ... (similar query for reward prediction) ...

                    actionUtilities.put(action, utility);
                });
            }

            var bestActionEntry = actionUtilities.entrySet().stream().filter(entry -> entry.getValue() > AGENT_UTILITY_THRESHOLD_FOR_SELECTION) // Only consider actions with some positive utility
                    .max(Map.Entry.comparingByValue());

            if (bestActionEntry.isPresent()) {
                System.out.printf("Agent: Selecting action by max utility: %s (Utility: %.3f)%n", bestActionEntry.get().getKey().id, bestActionEntry.get().getValue());
                return Optional.of(bestActionEntry.get().getKey());
            }

            // 3. Final Fallback: Exploration (Random Action)
            if (random.nextDouble() < AGENT_RANDOM_ACTION_PROBABILITY) {
                System.out.println("Agent: No preferred action. Selecting randomly for exploration.");
                return Optional.of(availableActions.get(random.nextInt(availableActions.size())));
            } else {
                // If not exploring, and no good options, maybe pick the least bad one or idle. Let's pick random for now.
                System.out.println("Agent: No preferred action and not exploring. Selecting randomly.");
                return Optional.of(availableActions.get(random.nextInt(availableActions.size())));
            }
        }

        /**
         * Learns from the outcome (new state, reward) of the last executed action.
         */
        private void learnFromExperience(Atom currentStateAtom, double reward, long now) {
            if (previousState == null || lastAction == null) return; // Cannot learn without context

            // 1. Learn State Transition: PredictiveImplication(Sequence(PrevState, Action), CurrentState)
            var sequenceLink = mem.link(Type.SEQUENCE, Truth.TRUE, 0.8, null, previousState, lastAction);
            var transitionTruth = Truth.of(1.0, AGENT_DEFAULT_LEARNING_COUNT); // Observed transition
            var effectDelay = Time.range(1); // Assume minimal delay for now
            var transitionLink = mem.link(Type.PREDICTIVE_IMPLICATION, transitionTruth, 0.8, effectDelay, sequenceLink, currentStateAtom);
            // System.out.println("  Learn: Transition " + transitionLink.id() + " " + transitionLink.truth);

            // 2. Learn Reward Association (State-Reward): PredictiveImplication(CurrentState, Reward)
            // Normalize reward to [0, 1] for strength? Or use raw value? Let's normalize.
            var normalizedRewardStrength = Math.max(0.0, Math.min(1.0, (reward + 1.0) / 2.0)); // Simple [-1,1] -> [0,1] scaling; adjust if reward range differs
            var rewardTruth = Truth.of(normalizedRewardStrength, AGENT_DEFAULT_LEARNING_COUNT * 0.5); // Less count than transition
            var rewardNode = mem.node("Reward");

            if (rewardTruth.strength > 0.05 && rewardTruth.strength < 0.95) { // Learn if reward is non-neutral
                var stateRewardLink = mem.link(Type.PREDICTIVE_IMPLICATION, rewardTruth, 0.7, Time.NOW, currentStateAtom, rewardNode);
                // System.out.println("  Learn: State-Reward " + stateRewardLink.id() + " " + stateRewardLink.truth);
            }

            // 3. Learn Action Utility (Action -> GoodAction): Inheritance(Action, GoodAction)
            // This is a simple form of reinforcement learning update.
            var goodActionNode = mem.node("GoodAction");
            if (goodActionNode != null) {
                // Evidence strength based on reward sign, count based on learning rate
                var utilityEvidence = Truth.of(reward > 0 ? 1.0 : 0.0, AGENT_DEFAULT_LEARNING_COUNT);
                if (Math.abs(reward) > 0.01) { // Only update utility if reward is significant
                    var utilityLink = new Link(Type.INHERITANCE, List.of(lastAction.id, goodActionNode.id), utilityEvidence, null);
                    utilityLink.pri(0.7, now);
                    var learnedUtility = mem.learn(utilityLink); // Learn/revise the utility link
                    System.out.printf("  Learn: Action-Utility Update %s -> %s%n", learnedUtility.id, learnedUtility.truth());
                }
            }
        }

        private boolean goalAchieved() {
            // Shallow check is usually sufficient
            return currentGoal != null && query(currentGoal, 2).stream().anyMatch(r -> strong(r.inferred.truth()));
        }

        private boolean strong(Truth t) {
            return t.strength > 0.9 && t.conf() > 0.8;
        }
    }

}
