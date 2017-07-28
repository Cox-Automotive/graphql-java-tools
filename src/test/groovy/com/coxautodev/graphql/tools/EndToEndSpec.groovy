package com.coxautodev.graphql.tools

import graphql.GraphQL
import spock.lang.Shared
import spock.lang.Specification

/**
 * @author Andrew Potter
 */
class EndToEndSpec extends Specification {

    @Shared
    GraphQL gql

    def setupSpec() {
        gql = new GraphQL(EndToEndSpecKt.createSchema())
    }

    def "schema comments are used as descriptions"() {
        expect:
            gql.graphQLSchema.allTypesAsList.find { it.name == 'Type' }?.valueDefinitionMap?.TYPE_1?.description == "Item type 1"
    }

    def "generated schema should respond to simple queries"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    items(itemsInput: {name: "item1"}) {
                        id
                        type
                    }
                }
                '''
            }

        then:
            noExceptionThrown()
    }

    def "generated schema should respond to simple mutations"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql, [name: "new1", type: Type.TYPE_2.toString()]) {
                '''
                mutation addNewItem($name: String!, $type: Type!) {
                    addItem(newItem: {name: $name, type: $type}) {
                        id
                        name
                        type
                    }
                }
                '''
            }

        then:
            data.addItem
    }

    def "generated schema should execute the subscription query"() {
        when:
            def newItem = new Item(1, "item", Type.TYPE_1, UUID.randomUUID(), [])
            def data = Utils.assertNoGraphQlErrors(gql, [:], new OnItemCreatedContext(newItem)) {
                '''
                subscription {
                    onItemCreated {
                        id
                    }
                } 
                '''
            }

        then:
            data.onItemCreated
    }

    def "generated schema should handle interface types"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    itemsByInterface {
                        name
                        type
                    }
                }
                '''
            }

        then:
            data.itemsByInterface
    }

    def "generated schema should handle union types"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    allItems {
                        ... on Item {
                            id
                            name
                        }
                        ... on OtherItem {
                            name
                            type
                        }
                    }
                }
                '''
            }

        then:
            data.allItems
    }

    def "generated schema should handle scalar types"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    itemByUUID(uuid: "38f685f1-b460-4a54-a17f-7fd69e8cf3f8") {
                        uuid
                    }
                }
                '''
            }

        then:
            data.itemByUUID
    }

    def "generated schema should handle optional arguments"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    missing: itemsWithOptionalInput {
                        id
                    }
                    
                    present: itemsWithOptionalInput(itemsInput: {name: "item1"}) {
                        id
                    }
                }
                '''
            }

        then:
            data.missing?.size > 1
            data.present?.size == 1
    }

    def "generated schema should handle optional arguments using java.util.Optional"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    missing: itemsWithOptionalInputExplicit {
                        id
                    }
                    
                    present: itemsWithOptionalInputExplicit(itemsInput: {name: "item1"}) {
                        id
                    }
                }
                '''
            }

        then:
            data.missing?.size > 1
            data.present?.size == 1
    }

    def "generated schema should handle optional return types using java.util.Optional"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    missing: optionalItem(itemsInput: {name: "item?"}) {
                        id
                    }
                    
                    present: optionalItem(itemsInput: {name: "item1"}) {
                        id
                    }
                }
                '''
            }

        then:
            data.missing == null
            data.present
    }

    def "generated schema should pass default arguments"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    defaultArgument
                }
                '''
            }

        then:
            data.defaultArgument == true
    }

    def "generated schema should return null without errors for null value with nested fields"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    complexNullableType {
                        first
                        second
                        third
                    }
                }
                '''
            }

        then:
            data.containsKey('complexNullableType')
            data.complexNullableType == null
    }

    def "generated schema handles nested lists in input type fields"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    complexInputType(complexInput: [[{first: "foo", second: [[{first: "bar"}]]}]])
                }
                '''
            }

        then:
            data.complexInputType
    }

    def "generated schema should use type extensions"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    extendedType {
                        first
                        second
                    }
                }
                '''
            }

        then:
            data.extendedType
            data.extendedType.first
            data.extendedType.second
    }

    def "generated schema uses properties if no methods are found"() {
        when:
            def data = Utils.assertNoGraphQlErrors(gql) {
                '''
                {
                    propertyField
                }
                '''
            }

        then:
            data.propertyField
    }
}
