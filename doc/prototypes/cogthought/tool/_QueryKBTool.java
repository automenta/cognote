package dumb.cogthought.tool;

import dumb.cogthought.Assertion;
import dumb.cogthought.Term;
import dumb.cogthought.Tool;
import dumb.cogthought.ToolContext;
import dumb.cogthought.util.Log;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;
import static dumb.cogthought.util.Log.info;
import static dumb.cogthought.util.Log.warn;

/**
 * Primitive Tool for querying the Knowledge Base.
 * Expected parameters: (QueryKB <query-type> <pattern> [<request-id>] [<options>])
 * <query-type> is an Atom (e.g., "ask-bindings", "ask-true-false").
 * <pattern> is a Term (typically a Lst).
 * <request-id> is an optional Atom representing the original API request ID.
 * <options> is an optional Lst of key-value pairs (e.g., (maxDepth 5)).
 *
 * This tool asserts the result back into the KB as an ApiResponse term:
 * (ApiResponse <request-id> (QueryResult <query-type> <status> <results> [<explanation>]))
 * where <status> is an Atom ("SUCCESS", "FAILURE", "ERROR")
 * and <results> is a Lst containing the results (e.g., list of bindings, boolean atom).
 */
public class _QueryKBTool implements Tool {

    private final ToolContext context;

    public _QueryKBTool(ToolContext context) {
        this.context = requireNonNull(context);
    }

    @Override
    public String name() {
        return "_QueryKB";
    }

    @Override
    public String description() {
        return "Queries the Knowledge Base. Parameters: (<query-type> <pattern> [<request-id>] [<options>]). Asserts result as (ApiResponse <request-id> (QueryResult ...)).";
    }

    @Override
    public CompletableFuture<?> execute(Term parameters, ToolContext context) {
        return CompletableFuture.supplyAsync(() -> {
            if (!(parameters instanceof Term.Lst params) || params.size() < 3) {
                throw new ToolExecutionException("Invalid parameters for _QueryKB tool. Expected (<query-type> <pattern> [<request-id>] [<options>])");
            }

            Term queryTypeTerm = params.get(1);
            Term patternTerm = params.get(2);
            Term requestIdTerm = params.size() > 3 ? params.get(3) : Term.Atom.of("tool-query-" + java.util.UUID.randomUUID()); // Generate ID if not provided
            Term optionsTerm = params.size() > 4 ? params.get(4) : Term.Lst.of(); // Optional options

            if (!(queryTypeTerm instanceof Term.Atom queryTypeAtom)) {
                 throw new ToolExecutionException("Second parameter (query-type) must be an Atom.");
            }
            if (!(requestIdTerm instanceof Term.Atom requestIdAtom)) {
                 warn("_QueryKB tool: Optional request-id parameter should be an Atom, got " + requestIdTerm.getClass().getSimpleName());
                 requestIdAtom = Term.Atom.of("tool-query-" + java.util.UUID.randomUUID()); // Fallback to generated ID
            }
            if (!(optionsTerm instanceof Term.Lst optionsList)) {
                 warn("_QueryKB tool: Optional options parameter should be a Lst, got " + optionsTerm.getClass().getSimpleName());
                 optionsList = Term.Lst.of(); // Fallback to empty list
            }

            String queryType = queryTypeAtom.name();
            String requestId = requestIdAtom.name();

            info(String.format("Executing _QueryKB tool: type=%s, pattern=%s, requestId=%s", queryType, patternTerm.toKif(), requestId));

            Term resultTerm;
            Term statusTerm;
            String explanation = null;

            try {
                // TODO: Implement different query types based on the queryType parameter
                // For now, this tool only supports a basic 'query' type which maps to KnowledgeBase.queryAssertions
                // More complex query types (ask-bindings, ask-true-false, achieve-goal) would involve
                // interacting with the TermLogicEngine's query capabilities (if exposed) or
                // triggering rules that perform backward chaining etc.

                if ("query".equals(queryType)) {
                    if (!(patternTerm instanceof Term.Lst patternList)) {
                         throw new ToolExecutionException("Pattern for 'query' type must be a Lst.");
                    }
                    List<Term> results = context.getKnowledgeBase().queryAssertions(patternList)
                                               .map(Assertion::kif) // Return the KIF term of matching assertions
                                               .collect(Collectors.toList());
                    resultTerm = new Term.Lst(results); // Wrap results in a list
                    statusTerm = Term.Atom.of("SUCCESS");
                    info(String.format("_QueryKB tool 'query' results for pattern %s: %d matches", patternTerm.toKif(), results.size()));

                } else {
                    // TODO: Implement other query types (ask-bindings, ask-true-false, etc.)
                    warn("_QueryKB tool: Unsupported query type: " + queryType);
                    resultTerm = Term.Str.of("Unsupported query type: " + queryType);
                    statusTerm = Term.Atom.of("FAILURE");
                    explanation = "Unsupported query type: " + queryType;
                }

            } catch (Exception e) {
                error("_QueryKB tool execution failed for request " + requestId + ": " + e.getMessage());
                e.printStackTrace();
                resultTerm = Term.Str.of("Error executing query: " + e.getMessage());
                statusTerm = Term.Atom.of("ERROR");
                explanation = e.getMessage();
            }

            // Construct the ApiResponse term: (ApiResponse <requestId> (QueryResult <query-type> <status> <results> [<explanation>]))
            List<Term> queryResultTerms = new java.util.ArrayList<>();
            queryResultTerms.add(Term.Atom.of("QueryResult"));
            queryResultTerms.add(queryTypeTerm);
            queryResultTerms.add(statusTerm);
            queryResultTerms.add(resultTerm);
            if (explanation != null) {
                 queryResultTerms.add(Term.Str.of(explanation));
            }
            Term apiResponseContent = new Term.Lst(queryResultTerms);

            Term apiResponseTerm = new Term.Lst(List.of(Term.Atom.of("ApiResponse"), requestIdAtom, apiResponseContent));

            // Assert the ApiResponse term into the KB
            // TODO: Define a specific KB ID for API responses in Ontology/Config
            String apiOutboxKbId = "api-outbox"; // Placeholder KB ID
            context.getKnowledgeBase().saveAssertion(new Assertion(
                dumb.cogthought.Cog.id(dumb.cogthought.Cog.ID_PREFIX_ASSERTION),
                (Term.Lst) apiResponseTerm,
                0.8, // Priority for API responses? Needs Ontology/Config
                java.time.Instant.now().toEpochMilli(), // Use epoch milliseconds
                null, // Source Note ID? Maybe the tool's Note ID?
                java.util.Collections.emptySet(), // No justifications
                Assertion.Type.GROUND, // Responses are typically ground facts
                false, false, false,
                java.util.Collections.emptyList(),
                0, true, // Active by default
                apiOutboxKbId // KB ID
            ));
            info("Asserted ApiResponse into KB for request " + requestId + ": " + apiResponseTerm.toKif());

            return null; // Primitive tools can return null or a simple success indicator
        }, context.getExecutor()); // Use the shared executor from ToolContext
    }
}
