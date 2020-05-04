package grakn.verification.resolution.resolve;

import grakn.client.GraknClient.Transaction;
import grakn.client.answer.ConceptMap;
import grakn.client.answer.Explanation;
import grakn.client.concept.Concept;
import graql.lang.Graql;
import graql.lang.pattern.Conjunction;
import graql.lang.pattern.Pattern;
import graql.lang.property.HasAttributeProperty;
import graql.lang.property.IsaProperty;
import graql.lang.property.RelationProperty;
import graql.lang.property.TypeProperty;
import graql.lang.property.VarProperty;
import graql.lang.query.GraqlGet;
import graql.lang.statement.Statement;
import graql.lang.statement.StatementAttribute;
import graql.lang.statement.StatementInstance;
import graql.lang.statement.Variable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.collect.Iterables.getOnlyElement;

public class QueryBuilder {

    private int nextVarIndex = 0;

    public List<GraqlGet> buildMatchGet(Transaction tx, GraqlGet query) {
            List<ConceptMap> answers = tx.execute(query);

            ArrayList<GraqlGet> resolutionQueries = new ArrayList<>();
            for (ConceptMap answer : answers) {
                resolutionQueries.add(Graql.match(resolutionStatements(tx, answer)).get());
            }
            return resolutionQueries;
    }

    private Set<Statement> resolutionStatements(Transaction tx, ConceptMap answer) {

        Pattern qp = answer.queryPattern();

        if (qp == null) {
            throw new RuntimeException("Answer is missing a pattern. Either patterns are broken or the initial query did not require inference.");
        }

        Set<Statement> answerStatements = removeIdStatements(qp.statements());
        answerStatements.addAll(generateKeyStatements(tx, answer.map()));

        if (answer.hasExplanation()) {

            Set<Statement> thenStatements = qp.statements();

            Explanation explanation = answer.explanation();

            if (explanation.getAnswers().size() == 1) {

                ConceptMap explAns = getOnlyElement(explanation.getAnswers());

                answerStatements.addAll(resolutionStatements(tx, explAns));
                Set<Statement> whenStatements = new LinkedHashSet<>(Objects.requireNonNull(explAns.queryPattern()).statements());

                String ruleLabel = explanation.getRule().label().toString();
                answerStatements.addAll(inferenceStatements(whenStatements, thenStatements, ruleLabel));
            } else {
                for (ConceptMap explAns : explanation.getAnswers()) {
                    answerStatements.addAll(resolutionStatements(tx, explAns));
                }
            }
        }
        return answerStatements;
    }

    public Set<Statement> inferenceStatements(Set<Statement> whenStatements, Set<Statement> thenStatements, String ruleLabel) {

        String inferenceType = "resolution";
        String inferenceRuleLabelType = "rule-label";

        Statement relation = Graql.var().isa(inferenceType).has(inferenceRuleLabelType, ruleLabel);

        LinkedHashMap<String, Statement> whenProps = new LinkedHashMap<>();

        for (Statement whenStatement : whenStatements) {
            whenProps.putAll(statementToProperties(whenStatement));
        }

        for (String whenVar : whenProps.keySet()) {
            relation = relation.rel("body", Graql.var(whenVar));
        }

//        ==================

        LinkedHashMap<String, Statement> thenProps = new LinkedHashMap<>();

        for (Statement thenStatement : thenStatements) {
            thenProps.putAll(statementToProperties(thenStatement));
        }

        for (String thenVar : thenProps.keySet()) {
            relation = relation.rel("head", Graql.var(thenVar));
        }

        LinkedHashSet<Statement> result = new LinkedHashSet<>();
        result.addAll(whenProps.values());
        result.addAll(thenProps.values());
        result.add(relation);
        return result;
    }

    private String getNextVar(){
        String nextVar = "x" + nextVarIndex;
        nextVarIndex ++;
        return nextVar;
    }

    public LinkedHashMap<String, Statement> statementToProperties(Statement statement) {
        LinkedHashMap<String, Statement> props = new LinkedHashMap<>();

        String statementVar = statement.var().name();

        for (VarProperty varProp : statement.properties()) {

            if (varProp instanceof HasAttributeProperty) {
                String nextVar = getNextVar();
                StatementInstance propStatement = Graql.var(nextVar).isa("has-attribute-property").has((HasAttributeProperty) varProp).rel("owner", statementVar);
                props.put(nextVar, propStatement);

            } else if (varProp instanceof RelationProperty){
                for (RelationProperty.RolePlayer rolePlayer : ((RelationProperty)varProp).relationPlayers()) {
                    Optional<Statement> role = rolePlayer.getRole();

                    String nextVar = getNextVar();

                    StatementInstance propStatement = Graql.var(nextVar).isa("relation-property").rel("rel", statementVar).rel("roleplayer", Graql.var(rolePlayer.getPlayer().var()));
                    if(role.isPresent()) {
                        String roleLabel = ((TypeProperty) getOnlyElement(role.get().properties())).name();
                        propStatement = propStatement.has("role-label", roleLabel);
                    }
                    props.put(nextVar, propStatement);
                }
            } else if (varProp instanceof IsaProperty){
                String nextVar = getNextVar();
                StatementInstance propStatement = Graql.var(nextVar).isa("isa-property").rel("instance", statementVar).has("type-label", varProp.property());
                props.put(nextVar, propStatement);
            }
        }
        return props;
    }

    public static Pattern makeAnonVarsExplicit(Pattern pattern) {
        return new Conjunction<>(pattern.statements().stream().map(QueryBuilder::makeAnonVarExplicit).collect(Collectors.toSet()));
    }

    private static Statement makeAnonVarExplicit(Statement statement) {

        if (statement.var().isReturned()) {
            return statement;
        } else {
            return Statement.create(statement.var().asReturnedVar(), statement.properties());
        }
    }

    /**
     * Remove statements that stipulate ConceptIds from a given set of statements
     * @param statements set of statements to remove from
     * @return set of statements without any referring to ConceptIds
     */
    public static Set<Statement> removeIdStatements(Set<Statement> statements) {
        LinkedHashSet<Statement> withoutIds = new LinkedHashSet<>();

        for (Statement statement : statements) {
//            statement.properties().forEach(varProperty -> varProperty.uniquelyIdentifiesConcept());
            boolean containsId = statement.toString().contains(" id ");
            if (!containsId) {
                withoutIds.add(statement);
            }
        }
        return withoutIds;
    }

    /**
     * Create a set of statements that will query for the keys of the concepts given in the map. Attributes given in
     * the map are simply queried for by their own type and value.
     * @param varMap variable map of concepts
     * @return Statements that check for the keys of the given concepts
     */
    public static Set<Statement> generateKeyStatements(Transaction tx, Map<Variable, Concept<?>> varMap) {
        LinkedHashSet<Statement> statements = new LinkedHashSet<>();

        for (Map.Entry<Variable, Concept<?>> entry : varMap.entrySet()) {
            Variable var = entry.getKey();
            Concept<?> concept = entry.getValue();

            if (concept.isAttribute()) {

                Statement statement = Graql.var(var);
                StatementAttribute s = null;

                Object attrValue = concept.asAttribute().value();
                if (attrValue instanceof String) {
                    s = statement.val((String) attrValue);
                } else if (attrValue instanceof Double) {
                    s = statement.val((Double) attrValue);
                } else if (attrValue instanceof Long) {
                    s = statement.val((Long) attrValue);
                } else if (attrValue instanceof LocalDateTime) {
                    s = statement.val((LocalDateTime) attrValue);
                } else if (attrValue instanceof Boolean) {
                    s = statement.val((Boolean) attrValue);
                }
                statements.add(s);

            } else if (concept.isEntity() | concept.isRelation()){

                concept.asThing().asRemote(tx).keys().forEach(attribute -> {

                    String typeLabel = attribute.type().label().toString();
                    Statement statement = Graql.var(var);
                    Object attrValue = attribute.value();

                    StatementInstance s = null;
                    if (attrValue instanceof String) {
                        s = statement.has(typeLabel, (String) attrValue);
                    } else if (attrValue instanceof Double) {
                        s = statement.has(typeLabel, (Double) attrValue);
                    } else if (attrValue instanceof Long) {
                        s = statement.has(typeLabel, (Long) attrValue);
                    } else if (attrValue instanceof LocalDateTime) {
                        s = statement.has(typeLabel, (LocalDateTime) attrValue);
                    } else if (attrValue instanceof Boolean) {
                        s = statement.has(typeLabel, (Boolean) attrValue);
                    }
                    statements.add(s);
                });

            } else {
                throw new RuntimeException("Presently we only handle queries concerning Things, not Types");
            }
        }
        return statements;
    }

}