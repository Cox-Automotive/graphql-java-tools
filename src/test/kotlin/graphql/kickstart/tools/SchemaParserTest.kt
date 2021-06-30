package graphql.kickstart.tools

import graphql.kickstart.tools.resolver.FieldResolverError
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLNonNull
import graphql.schema.idl.SchemaDirectiveWiring
import graphql.schema.idl.SchemaDirectiveWiringEnvironment
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.springframework.aop.framework.ProxyFactory
import java.io.FileNotFoundException
import java.util.concurrent.Future

class SchemaParserTest {
    private lateinit var builder: SchemaParserBuilder

    @Rule
    @JvmField
    var expectedEx: ExpectedException = ExpectedException.none()

    @Before
    fun setup() {
        builder = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    get(int: Int!): Int!
                }
                """)
    }

    @Test(expected = FileNotFoundException::class)
    fun `builder throws FileNotFound exception when file is missing`() {
        builder.file("/404").build()
    }

    @Test
    fun `builder doesn't throw FileNotFound exception when file is present`() {
        SchemaParser.newParser().file("Test.graphqls")
            .resolvers(object : GraphQLQueryResolver {
                fun getId(): String = "1"
            })
            .build()
    }

    @Test(expected = SchemaClassScannerError::class)
    fun `parser throws SchemaError when Query resolver is missing`() {
        builder.build().makeExecutableSchema()
    }

    @Test(expected = FieldResolverError::class)
    fun `parser throws ResolverError when Query resolver is given without correct method`() {
        SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    get(int: Int!): Int!
                }
                """)
            .resolvers(object : GraphQLQueryResolver {})
            .build()
            .makeExecutableSchema()
    }

    @Test
    fun `parser should parse correctly when Query resolver is given`() {
        SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    get(int: Int!): Int!
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun get(i: Int): Int = i
            })
            .build()
            .makeExecutableSchema()
    }

    @Test
    fun `parser should parse correctly when multiple query resolvers are given`() {
        SchemaParser.newParser()
            .schemaString(
                """
                type Obj {
                    name: String
                }
    
                type AnotherObj {
                    key: String
                }
    
                type Query {
                    obj: Obj
                    anotherObj: AnotherObj
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun getObj(): Obj = Obj()
            }, object : GraphQLQueryResolver {
                fun getAnotherObj(): AnotherObj = AnotherObj()
            })
            .build()
            .makeExecutableSchema()
    }

    @Test
    fun `parser should parse correctly when multiple resolvers for the same data type are given`() {
        SchemaParser.newParser()
            .schemaString(
                """
                type RootObj {
                    obj: Obj
                    anotherObj: AnotherObj
                }
                
                type Obj {
                    name: String
                }
                
                type AnotherObj {
                    key: String
                }
                
                type Query {
                    rootObj: RootObj
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun getRootObj(): RootObj {
                    return RootObj()
                }
            }, object : GraphQLResolver<RootObj> {
                fun getObj(rootObj: RootObj): Obj {
                    return Obj()
                }
            }, object : GraphQLResolver<RootObj> {
                fun getAnotherObj(rootObj: RootObj): AnotherObj {
                    return AnotherObj()
                }
            })
            .build()
            .makeExecutableSchema()
    }

    @Test
    fun `parser should allow setting custom generic wrappers`() {
        SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    one: Object!
                    two: Object!
                }
                
                type Object {
                    name: String!
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun one(): CustomGenericWrapper<Int, Obj>? = null
                fun two(): Obj? = null
            })
            .options(SchemaParserOptions.newOptions().genericWrappers(SchemaParserOptions.GenericWrapper(CustomGenericWrapper::class, 1)).build())
            .build()
            .makeExecutableSchema()
    }

    @Test(expected = SchemaClassScannerError::class)
    fun `parser should allow turning off default generic wrappers`() {
        SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    one: Object!
                    two: Object!
                }
                
                type Object {
                    toString: String!
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun one(): Future<Obj>? = null
                fun two(): Obj? = null
            })
            .options(SchemaParserOptions.newOptions().useDefaultGenericWrappers(false).build())
            .build()
            .makeExecutableSchema()
    }

    @Test
    fun `parser should throw descriptive exception when object is used as input type incorrectly`() {
        expectedEx.expect(SchemaError::class.java)
        expectedEx.expectMessage("Was a type only permitted for object types incorrectly used as an input type, or vice-versa")

        SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    name(filter: Filter): [String]
                }
                
                type Filter {
                    filter: String
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun name(filter: Filter): List<String>? = null
            })
            .build()
            .makeExecutableSchema()

        throw AssertionError("should not be called")
    }

    @Test
    fun `parser handles spring AOP proxied resolvers by default`() {
        val resolver = ProxyFactory(ProxiedResolver()).proxy as GraphQLQueryResolver

        SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    test: [String]
                }
                """)
            .resolvers(resolver)
            .build()
    }

    @Test
    fun `parser handles enums with overridden toString method`() {
        SchemaParser.newParser()
            .schemaString(
                """
                enum CustomEnum {
                    FOO
                }
                
                type Query {
                    customEnum: CustomEnum
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun customEnum(): CustomEnum? = null
            })
            .build()
            .makeExecutableSchema()
    }

    @Test
    fun `parser should include source location for field definition`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                |type Query {
                |    id: ID!
                |}
                """.trimMargin())
            .resolvers(QueryWithIdResolver())
            .build()
            .makeExecutableSchema()

        val sourceLocation = schema.getObjectType("Query")
            .getFieldDefinition("id")
            .definition.sourceLocation
        assertNotNull(sourceLocation)
        assertEquals(sourceLocation.line, 2)
        assertEquals(sourceLocation.column, 5)
        assertNull(sourceLocation.sourceName)
    }

    @Test
    fun `parser should include source location for field definition when loaded from single classpath file`() {
        val schema = SchemaParser.newParser()
            .file("Test.graphqls")
            .resolvers(QueryWithIdResolver())
            .build()
            .makeExecutableSchema()

        val sourceLocation = schema.getObjectType("Query")
            .getFieldDefinition("id")
            .definition.sourceLocation
        assertNotNull(sourceLocation)
        assertEquals(sourceLocation.line, 2)
        assertEquals(sourceLocation.column, 3)
        assertEquals(sourceLocation.sourceName, "Test.graphqls")
    }

    @Test
    fun `support enum types if only used as input type`() {
        SchemaParser.newParser()
            .schemaString(
                """
                type Query { test: Boolean }
                        
                type Mutation {
                    save(input: SaveInput!): Boolean
                }
                
                input SaveInput {
                    type: EnumType!
                }
                
                enum EnumType {
                    TEST
                }
                """)
            .resolvers(object : GraphQLMutationResolver {
                fun save(input: SaveInput): Boolean = false
                inner class SaveInput {
                    var type: EnumType? = null
                }
            }, object : GraphQLQueryResolver {
                fun test(): Boolean = false
            })
            .dictionary(EnumType::class)
            .build()
            .makeExecutableSchema()
    }

    @Test
    fun `support enum types if only used in input Map`() {
        SchemaParser.newParser()
            .schemaString(
                """
                type Query { test: Boolean }
                        
                type Mutation {
                    save(input: SaveInput!): Boolean
                }
                
                input SaveInput {
                    age: Int
                    type: EnumType!
                }
                
                enum EnumType {
                    TEST
                }
                """)
            .resolvers(object : GraphQLMutationResolver {
                fun save(input: Map<*, *>): Boolean = false
            }, object : GraphQLQueryResolver {
                fun test(): Boolean = false
            })
            .dictionary(EnumType::class)
            .build()
            .makeExecutableSchema()
    }

    @Test
    fun `allow circular relations in input objects`() {
        SchemaParser.newParser()
            .schemaString(
                """
                input A {
                    id: ID!
                    b: B
                }
                input B {
                    id: ID!
                    a: A
                }
                input C {
                    id: ID!
                    c: C
                }
                type Query { test: Boolean }
                type Mutation {
                    test(input: A!): Boolean
                    testC(input: C!): Boolean
                }
                """)
            .resolvers(object : GraphQLMutationResolver {
                inner class A {
                    var id: String? = null
                    var b: B? = null
                }

                inner class B {
                    var id: String? = null
                    var a: A? = null
                }

                inner class C {
                    var id: String? = null
                    var c: C? = null
                }

                fun test(a: A): Boolean {
                    return true
                }

                fun testC(c: C): Boolean {
                    return true
                }
            }, object : GraphQLQueryResolver {
                fun test(): Boolean = false
            })
            .build()
            .makeExecutableSchema()
    }

    @Test
    fun `interface implementing an interface should have non-empty interface list`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                interface Trait {
                    id: ID!
                }
                interface MammalTrait implements Trait {
                    id: ID!
                }
                type PoodleTrait implements Trait & MammalTrait {
                    id: ID!
                }

                interface Animal {
                    id: ID!
                    traits: [Trait]
                }
                interface Dog implements Animal {
                    id: ID!
                    traits: [MammalTrait]
                }
                type Poodle implements Animal & Dog {
                    id: ID!
                    traits: [PoodleTrait]
                }

                type Query { test: [Poodle] }
                """)
            .resolvers(MultiLevelInterfaceResolver())
            .build()
            .makeExecutableSchema()
        val traitInterface = schema.getType("Trait") as GraphQLInterfaceType
        val animalInterface = schema.getType("Animal") as GraphQLInterfaceType
        val mammalTraitInterface = schema.getType("MammalTrait") as GraphQLInterfaceType
        val dogInterface = schema.getType("Dog") as GraphQLInterfaceType
        val poodleObject = schema.getType("Poodle") as GraphQLObjectType
        val poodleTraitObject = schema.getType("PoodleTrait") as GraphQLObjectType

        assert(poodleObject.interfaces.containsAll(listOf(dogInterface, animalInterface)))
        assert(poodleTraitObject.interfaces.containsAll(listOf(mammalTraitInterface, traitInterface)))
        assert(dogInterface.interfaces.contains(animalInterface))
        assert(mammalTraitInterface.interfaces.contains(traitInterface))
        assert(traitInterface.definition.implements.isEmpty())
        assert(animalInterface.definition.implements.isEmpty())
    }

    class MultiLevelInterfaceResolver : GraphQLQueryResolver {
        fun test(): List<Poodle> = listOf()

        interface Trait {
            var id: String
        }

        interface MammalTrait : Trait {
            override var id: String
        }

        interface PoodleTrait : MammalTrait {
            override var id: String
        }

        abstract class Animal<T : Trait> {
            var id: String? = null
            abstract var traits: List<T>
        }

        abstract class Dog<T : MammalTrait> : Animal<T>() {
            abstract override var traits: List<T>
        }

        class Poodle(override var traits: List<PoodleTrait>) : Dog<PoodleTrait>()
    }

    @Test
    fun `NonNull and nullable input arguments should resolve to GraphQLInputObjectType`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    testNonNullable(filter: Filter!): Boolean
                    testNullable(filter: Filter): Boolean
                }
        
                input Filter {
                    filter: String
                }
                """)
            .resolvers(object : GraphQLQueryResolver {
                fun testNonNullable(filter: Filter): Boolean = false
                fun testNullable(filter: Filter): Boolean = false
            })
            .directiveWiring(object : SchemaDirectiveWiring {
                override fun onArgument(environment: SchemaDirectiveWiringEnvironment<GraphQLArgument>): GraphQLArgument {
                    when (environment.element.type) {
                        is GraphQLNonNull ->
                            assert((environment.element.type as GraphQLNonNull).wrappedType is GraphQLInputObjectType)
                    }
                    return environment.element
                }
            })
            .build()
            .makeExecutableSchema()

        val testNonNullableArgument = schema.getObjectType("Query")
            .getFieldDefinition("testNonNullable")
            .arguments.first()
        val testNullableArgument = schema.getObjectType("Query")
            .getFieldDefinition("testNullable")
            .arguments.first()
        assert(testNonNullableArgument.type is GraphQLNonNull)
        assert((testNonNullableArgument.type as GraphQLNonNull).wrappedType is GraphQLInputObjectType)
        assert(testNullableArgument.type is GraphQLInputObjectType)
    }

    @Test
    fun `parser should use comments for descriptions`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    "description"
                    description: String
                    #comment
                    comment: String
                    omitted: String
                    "description"
                    #comment
                    both: String
                    ""
                    empty: String
                }
                """)
            .resolvers(object : GraphQLQueryResolver {})
            .options(SchemaParserOptions.newOptions().allowUnimplementedResolvers(true).build())
            .build()
            .makeExecutableSchema()

        val queryType = schema.getObjectType("Query")
        assertEquals(queryType.getFieldDefinition("description").description, "description")
        assertEquals(queryType.getFieldDefinition("comment").description, "comment")
        assertNull(queryType.getFieldDefinition("omitted").description)
        assertEquals(queryType.getFieldDefinition("both").description, "description")
        assertEquals(queryType.getFieldDefinition("empty").description, "")
    }

    @Test
    fun `parser should not use comments for descriptions`() {
        val schema = SchemaParser.newParser()
            .schemaString(
                """
                type Query {
                    "description"
                    description: String
                    #comment
                    comment: String
                    omitted: String
                    "description"
                    #comment
                    both: String
                    ""
                    empty: String
                }
                """)
            .resolvers(object : GraphQLQueryResolver {})
            .options(SchemaParserOptions.newOptions().useCommentsForDescriptions(false).allowUnimplementedResolvers(true).build())
            .build()
            .makeExecutableSchema()

        assertEquals(schema.queryType.getFieldDefinition("description").description, "description")
        assertNull(schema.queryType.getFieldDefinition("comment").description)
        assertNull(schema.queryType.getFieldDefinition("omitted").description)
        assertEquals(schema.queryType.getFieldDefinition("both").description, "description")
        assertEquals(schema.queryType.getFieldDefinition("empty").description, "")
    }

    enum class EnumType {
        TEST
    }

    class QueryWithIdResolver : GraphQLQueryResolver {
        fun getId(): String? = null
    }

    class Filter {
        fun filter(): String? = null
    }

    class CustomGenericWrapper<T, V>

    class Obj {
        fun name() = null
    }

    class AnotherObj {
        fun key() = null
    }

    class RootObj

    class ProxiedResolver : GraphQLQueryResolver {
        fun test(): List<String> = listOf()
    }

    enum class CustomEnum {
        FOO {
            override fun toString(): String {
                return "Bar"
            }
        }
    }
}
