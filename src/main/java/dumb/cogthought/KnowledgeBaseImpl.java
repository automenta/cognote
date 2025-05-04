package dumb.cogthought;

import dumb.cogthought.persistence.FilePersistence;
import dumb.cogthought.util.Log;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static dumb.cogthought.Logic.KIF_OP_NOT;
import static dumb.cogthought.util.Log.error;
import static dumb.cogthought.util.Log.info;
import static dumb.cogthought.util.Log.message;

public class KnowledgeBaseImpl implements KnowledgeBase {

    private static final String NOTES_PREFIX = "notes/";
    private static final String ASSERTIONS_PREFIX = "assertions/";
    private static final String RULES_PREFIX = "rules/";

    private final Persistence persistence;

    private final ConcurrentMap<String, Assertion> assertions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> justifications = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> dependents = new ConcurrentHashMap<>();
    private final ConcurrentMap<Term.Atom, Set<String>> predicateIndex = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Rule> rules = new ConcurrentHashMap<>();

    public KnowledgeBaseImpl(Persistence persistence) {
        this.persistence = requireNonNull(persistence);
        loadAllFromPersistence();
    }

    private void loadAllFromPersistence() {
        info("Loading Knowledge Base from persistence...");
        clearInternalState();

        persistence.listKeysByPrefix(ASSERTIONS_PREFIX)
                .forEach(key -> {
                    String id = key.substring(ASSERTIONS_PREFIX.length());
                    persistence.load(key, Assertion.class).ifPresent(this::addAssertionInternal);
                });

        rebuildTMSAndIndices();

        persistence.listKeysByPrefix(RULES_PREFIX)
                .forEach(key -> {
                    String id = key.substring(RULES_PREFIX.length());
                    persistence.load(key, Rule.class).ifPresent(rule -> rules.put(rule.id(), rule));
                });

        info("Knowledge Base loaded.");
    }

    private void clearInternalState() {
        assertions.clear();
        justifications.clear();
        dependents.clear();
        predicateIndex.clear();
        rules.clear();
    }

    private void addAssertionInternal(Assertion assertion) {
        assertions.put(assertion.id(), assertion);

        assertion.justificationIds().forEach(justId -> {
            dependents.computeIfAbsent(justId, k -> ConcurrentHashMap.newKeySet()).add(assertion.id());
        });

        assertion.getReferencedPredicates().forEach(pred -> {
            predicateIndex.computeIfAbsent(pred, k -> ConcurrentHashMap.newKeySet()).add(assertion.id());
        });
    }

    private void removeAssertionInternal(String assertionId) {
        Assertion assertion = assertions.remove(assertionId);
        if (assertion != null) {
            assertion.justificationIds().forEach(justId -> {
                Set<String> deps = dependents.get(justId);
                if (deps != null) {
                    deps.remove(assertionId);
                    if (deps.isEmpty()) {
                        dependents.remove(justId);
                    }
                }
            });

            assertion.getReferencedPredicates().forEach(pred -> {
                Set<String> ids = predicateIndex.get(pred);
                if (ids != null) {
                    ids.remove(assertionId);
                    if (ids.isEmpty()) {
                        predicateIndex.remove(pred);
                    }
                }
            });
        }
    }

    private void updateAssertionStatus(String assertionId) {
        Assertion assertion = assertions.get(assertionId);
        if (assertion == null) return;

        boolean isActive = assertion.justificationIds().isEmpty() ||
                       assertion.justificationIds().stream().allMatch(justId -> {
                           Assertion just = assertions.get(justId);
                           return just != null && just.isActive();
                       });

        if (assertion.isActive() != isActive) {
            Assertion updatedAssertion = assertion.withStatus(isActive);
            assertions.put(assertionId, updatedAssertion);
            persistence.save(ASSERTIONS_PREFIX + assertionId, updatedAssertion);

            dependents.getOrDefault(assertionId, Collections.emptySet())
                      .forEach(this::updateAssertionStatus);

            info("Assertion status changed: " + assertionId + " -> " + isActive);
        }
    }

    private void rebuildTMSAndIndices() {
        predicateIndex.clear();
        assertions.values().forEach(assertion -> {
            assertion.getReferencedPredicates().forEach(pred -> {
                predicateIndex.computeIfAbsent(pred, k -> ConcurrentHashMap.newKeySet()).add(assertion.id());
            });
        });

        dependents.clear();
        assertions.values().forEach(assertion -> {
            assertion.justificationIds().forEach(justId -> {
                dependents.computeIfAbsent(justId, k -> ConcurrentHashMap.newKeySet()).add(assertion.id());
            });
        });

        Queue<String> queue = new LinkedList<>();
        Set<String> visited = new HashSet<>();

        assertions.values().stream()
                  .filter(a -> a.justificationIds().isEmpty())
                  .map(Assertion::id)
                  .forEach(queue::offer);

        while(!queue.isEmpty()){
            String currentId = queue.poll();
            if(!visited.add(currentId)) continue;

            updateAssertionStatus(currentId);

            dependents.getOrDefault(currentId, Collections.emptySet())
                      .forEach(queue::offer);
        }

        assertions.keySet().stream()
                  .filter(id -> !visited.contains(id))
                  .forEach(this::updateAssertionStatus);
    }

    @Override
    public void saveNote(Note note) {
        persistence.save(NOTES_PREFIX + note.id(), note);
    }

    @Override
    public Optional<Note> loadNote(String id) {
        return persistence.load(NOTES_PREFIX + id, Note.class);
    }

    @Override
    public void deleteNote(String id) {
        persistence.delete(NOTES_PREFIX + id);
    }

    @Override
    public Stream<String> listNoteIds() {
        return persistence.listKeysByPrefix(NOTES_PREFIX).map(key -> key.substring(NOTES_PREFIX.length()));
    }

    @Override
    public void saveAssertion(Assertion assertion) {
        addAssertionInternal(assertion);
        persistence.save(ASSERTIONS_PREFIX + assertion.id(), assertion);
        updateAssertionStatus(assertion.id());
    }

    @Override
    public Optional<Assertion> loadAssertion(String id) {
        Assertion assertion = assertions.get(id);
        if (assertion != null) {
             return Optional.of(assertion);
        }
        return persistence.load(ASSERTIONS_PREFIX + id, Assertion.class)
                         .map(loaded -> {
                             assertions.computeIfAbsent(loaded.id(), k -> {
                                 addAssertionInternal(loaded);
                                 updateAssertionStatus(loaded.id());
                                 return loaded;
                             });
                             return assertions.get(loaded.id());
                         });
    }

    @Override
    public void deleteAssertion(String id) {
        removeAssertionInternal(id);
        persistence.delete(ASSERTIONS_PREFIX + id);
        dependents.getOrDefault(id, Collections.emptySet())
                  .forEach(this::updateAssertionStatus);
    }

    @Override
    public Stream<Assertion> queryAssertions(Term pattern) {
        if (!(pattern instanceof Term.Lst patternList) || patternList.terms.isEmpty()) {
            return Stream.empty();
        }

        Term predicateTerm = patternList.get(0);
        if (!(predicateTerm instanceof Term.Atom predicateAtom)) {
             return Stream.empty();
        }

        Set<String> candidateIds = predicateIndex.getOrDefault(predicateAtom, Collections.emptySet());

        return candidateIds.stream()
                           .map(assertions::get)
                           .filter(Objects::nonNull)
                           .filter(Assertion::isActive)
                           .filter(assertion -> {
                               Term effectivePattern = pattern;
                               Term effectiveAssertionTerm = assertion.getEffectiveTerm();

                               boolean patternIsNegated = effectivePattern.op().filter(KIF_OP_NOT::equals).isPresent();
                               boolean assertionIsNegated = assertion.negated();

                               if (patternIsNegated != assertionIsNegated) {
                                   return false;
                               }

                               if (patternIsNegated) {
                                   if (!(effectivePattern instanceof Term.Lst negPatternList) || negPatternList.size() != 2) {
                                       return false;
                                   }
                                   effectivePattern = negPatternList.get(1);
                               }

                               return Logic.unify(effectivePattern, effectiveAssertionTerm).isPresent();
                           });
    }

    @Override
    public void saveRule(Rule rule) {
        rules.put(rule.id(), rule);
        persistence.save(RULES_PREFIX + rule.id(), rule);
    }

    @Override
    public Optional<Rule> loadRule(String id) {
        Rule rule = rules.get(id);
        if (rule != null) {
            return Optional.of(rule);
        }
        return persistence.load(RULES_PREFIX + id, Rule.class)
                         .map(loaded -> {
                             rules.put(loaded.id(), loaded);
                             return loaded;
                         });
    }

    @Override
    public void deleteRule(String id) {
        rules.remove(id);
        persistence.delete(RULES_PREFIX + id);
    }

    @Override
    public Stream<String> listRuleIds() {
        return persistence.listKeysByPrefix(RULES_PREFIX).map(key -> key.substring(RULES_PREFIX.length()));
    }

    @Override
    public Stream<Rule> findMatchingRules(Term term) {
        return rules.values().stream()
                    .filter(rule -> Logic.unify(rule.antecedent(), term).isPresent());
    }

    @Override
    public void saveRelationship(String sourceNoteId, Relationship relationship) {
        loadNote(sourceNoteId).ifPresent(sourceNote -> {
            List<Relationship> graph = new ArrayList<>(sourceNote.graph());
            graph.removeIf(rel -> rel.targetId().equals(relationship.targetId()) && rel.type().equals(relationship.type()));
            graph.add(relationship);
            Note updatedNote = new Note(
                sourceNote.id(), sourceNote.type(), sourceNote.title(), sourceNote.text(),
                sourceNote.status(), sourceNote.priority(), sourceNote.color(), Instant.now(),
                sourceNote.metadata(), graph, sourceNote.associatedTerms()
            );
            saveNote(updatedNote);
        });
    }

    @Override
    public Stream<Relationship> loadRelationships(String sourceNoteId) {
        return loadNote(sourceNoteId)
                .map(note -> note.graph().stream())
                .orElse(Stream.empty());
    }

    @Override
    public void deleteRelationship(String sourceNoteId, String targetNoteId, String type) {
         loadNote(sourceNoteId).ifPresent(sourceNote -> {
            List<Relationship> graph = new ArrayList<>(sourceNote.graph());
            boolean changed = graph.removeIf(rel -> rel.targetId().equals(targetNoteId) && rel.type().equals(type));
            if (changed) {
                 Note updatedNote = new Note(
                    sourceNote.id(), sourceNote.type(), sourceNote.title(), sourceNote.text(),
                    sourceNote.status(), sourceNote.priority(), sourceNote.color(), Instant.now(),
                    sourceNote.metadata(), graph, sourceNote.associatedTerms()
                );
                saveNote(updatedNote);
            }
        });
    }

    @Override
    public void clear() {
        persistence.clear();
        clearInternalState();
        info("Knowledge Base cleared.");
    }
}
