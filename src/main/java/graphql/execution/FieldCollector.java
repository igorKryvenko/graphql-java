package graphql.execution;


import graphql.Internal;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLUnionType;
import graphql.schema.SchemaUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static graphql.execution.TypeFromAST.getTypeFromAST;

/**
 * A field collector can iterate over field selection sets and build out the sub fields that have been selected,
 * expanding named and inline fragments as it goes.s
 */
@Internal
public class FieldCollector {

    private ConditionalNodes conditionalNodes;

    private SchemaUtil schemaUtil = new SchemaUtil();

    public FieldCollector() {
        conditionalNodes = new ConditionalNodes();
    }


    /**
     * Given a list of fields this will collect the sub-field selections and return it as a map
     *
     * @param parameters the parameters to this method
     * @param fields     the list of fields to collect for
     *
     * @return a map of the sub field selections
     */
    public Map<String, List<Field>> collectFields(FieldCollectorParameters parameters, List<Field> fields) {
        Map<String, List<Field>> subFields = new LinkedHashMap<>();
        List<String> visitedFragments = new ArrayList<>();
        for (Field field : fields) {
            if (field.getSelectionSet() == null) {
                continue;
            }
            this.collectFields(parameters, field.getSelectionSet(), visitedFragments, subFields);
        }
        return subFields;
    }

    /**
     * Given a selection set this will collect the sub-field selections and return it as a map
     *
     * @param parameters   the parameters to this method
     * @param selectionSet the selection set to collect on
     *
     * @return a map of the sub field selections
     */
    public Map<String, List<Field>> collectFields(FieldCollectorParameters parameters, SelectionSet selectionSet) {
        Map<String, List<Field>> subFields = new LinkedHashMap<>();
        List<String> visitedFragments = new ArrayList<>();
        this.collectFields(parameters, selectionSet, visitedFragments, subFields);
        return subFields;
    }


    private void collectFields(FieldCollectorParameters parameters, SelectionSet selectionSet, List<String> visitedFragments, Map<String, List<Field>> fields) {

        for (Selection selection : selectionSet.getSelections()) {
            if (selection instanceof Field) {
                collectField(parameters, fields, (Field) selection);
            } else if (selection instanceof InlineFragment) {
                collectInlineFragment(parameters, visitedFragments, fields, (InlineFragment) selection);
            } else if (selection instanceof FragmentSpread) {
                collectFragmentSpread(parameters, visitedFragments, fields, (FragmentSpread) selection);
            }
        }
    }

    private void collectFragmentSpread(FieldCollectorParameters parameters, List<String> visitedFragments, Map<String, List<Field>> fields, FragmentSpread fragmentSpread) {
        if (visitedFragments.contains(fragmentSpread.getName())) {
            return;
        }
        if (!conditionalNodes.shouldInclude(parameters.getVariables(), fragmentSpread.getDirectives())) {
            return;
        }
        visitedFragments.add(fragmentSpread.getName());
        FragmentDefinition fragmentDefinition = parameters.getFragmentsByName().get(fragmentSpread.getName());

        if (!conditionalNodes.shouldInclude(parameters.getVariables(), fragmentDefinition.getDirectives())) {
            return;
        }
        if (!doesFragmentConditionMatch(parameters, fragmentDefinition)) {
            return;
        }
        collectFields(parameters, fragmentDefinition.getSelectionSet(), visitedFragments, fields);
    }

    private void collectInlineFragment(FieldCollectorParameters parameters, List<String> visitedFragments, Map<String, List<Field>> fields, InlineFragment inlineFragment) {
        if (!conditionalNodes.shouldInclude(parameters.getVariables(), inlineFragment.getDirectives()) ||
                !doesFragmentConditionMatch(parameters, inlineFragment)) {
            return;
        }
        collectFields(parameters, inlineFragment.getSelectionSet(), visitedFragments, fields);
    }

    private void collectField(FieldCollectorParameters parameters, Map<String, List<Field>> fields, Field field) {
        if (!conditionalNodes.shouldInclude(parameters.getVariables(), field.getDirectives())) {
            return;
        }
        String name = getFieldEntryKey(field);
        if (!fields.containsKey(name)) {
            fields.put(name, new ArrayList<>());
        }
        fields.get(name).add(field);
    }

    private String getFieldEntryKey(Field field) {
        if (field.getAlias() != null) return field.getAlias();
        else return field.getName();
    }


    private boolean doesFragmentConditionMatch(FieldCollectorParameters parameters, InlineFragment inlineFragment) {
        if (inlineFragment.getTypeCondition() == null) {
            return true;
        }
        GraphQLType conditionType;
        conditionType = getTypeFromAST(parameters.getGraphQLSchema(), inlineFragment.getTypeCondition());
        return checkTypeCondition(parameters, conditionType);
    }

    private boolean doesFragmentConditionMatch(FieldCollectorParameters parameters, FragmentDefinition fragmentDefinition) {
        GraphQLType conditionType;
        conditionType = getTypeFromAST(parameters.getGraphQLSchema(), fragmentDefinition.getTypeCondition());
        return checkTypeCondition(parameters, conditionType);
    }

    private boolean checkTypeCondition(FieldCollectorParameters parameters, GraphQLType conditionType) {
        GraphQLObjectType type = parameters.getObjectType();
        if (conditionType.equals(type)) {
            return true;
        }

        if (conditionType instanceof GraphQLInterfaceType) {
            List<GraphQLObjectType> implementations = schemaUtil.findImplementations(parameters.getGraphQLSchema(), (GraphQLInterfaceType) conditionType);
            return implementations.contains(type);
        } else if (conditionType instanceof GraphQLUnionType) {
            return ((GraphQLUnionType) conditionType).getTypes().contains(type);
        }
        return false;
    }


}
