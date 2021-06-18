package graphql.kickstart.tools

import graphql.kickstart.tools.SchemaParserOptions.Companion.defaultOptions
import graphql.kickstart.tools.resolver.FieldResolverError
import graphql.kickstart.tools.resolver.FieldResolverScanner
import graphql.kickstart.tools.resolver.MethodFieldResolver
import graphql.kickstart.tools.resolver.PropertyFieldResolver
import graphql.language.FieldDefinition
import graphql.language.TypeName
import graphql.relay.Connection
import graphql.relay.DefaultConnection
import graphql.relay.DefaultPageInfo
import org.junit.Test

class FieldResolverScannerTest {

    private val options = defaultOptions()
    private val scanner = FieldResolverScanner(options)

    @Test
    fun `scanner finds fields on multiple root types`() {
        val resolver = RootResolverInfo(listOf(RootQuery1(), RootQuery2()), options)

        val result1 = scanner.findFieldResolver(FieldDefinition("field1", TypeName("String")), resolver)
        val result2 = scanner.findFieldResolver(FieldDefinition("field2", TypeName("String")), resolver)

        assertNotEquals(result1.search.source, result2.search.source)
    }

    @Test(expected = FieldResolverError::class)
    fun `scanner throws exception when more than one resolver method is found`() {
        val resolver = RootResolverInfo(listOf(RootQuery1(), DuplicateQuery()), options)

        scanner.findFieldResolver(FieldDefinition("field1", TypeName("String")), resolver)
    }

    @Test(expected = FieldResolverError::class)
    fun `scanner throws exception when no resolver methods are found`() {
        val resolver = RootResolverInfo(listOf(), options)

        scanner.findFieldResolver(FieldDefinition("field1", TypeName("String")), resolver)
    }

    @Test
    fun `scanner finds properties when no method is found`() {
        val resolver = RootResolverInfo(listOf(PropertyQuery()), options)

        val name = scanner.findFieldResolver(FieldDefinition("name", TypeName("String")), resolver)
        val version = scanner.findFieldResolver(FieldDefinition("version", TypeName("Integer")), resolver)

        assert(name is PropertyFieldResolver)
        assert(version is PropertyFieldResolver)
    }

    @Test
    fun `scanner finds generic return type`() {
        val resolver = RootResolverInfo(listOf(GenericQuery()), options)

        val users = scanner.findFieldResolver(FieldDefinition("users", TypeName("UserConnection")), resolver)

        assert(users is MethodFieldResolver)
    }

    @Test
    fun `scanner prefers concrete resolver`() {
        val resolver = DataClassResolverInfo(Kayak::class.java)

        val meta = scanner.findFieldResolver(FieldDefinition("information", TypeName("VehicleInformation")), resolver)

        assert(meta is MethodFieldResolver)
        assertEquals((meta as MethodFieldResolver).method.returnType, BoatInformation::class.java)
    }

    @Test
    fun `scanner finds field resolver method using camelCase for snake_cased field_name`() {
        val resolver = RootResolverInfo(listOf(CamelCaseQuery1()), options)

        val meta = scanner.findFieldResolver(FieldDefinition("hull_type", TypeName("HullType")), resolver)

        assert(meta is MethodFieldResolver)
        assertEquals((meta as MethodFieldResolver).method.returnType, HullType::class.java)
    }

    @Test
    fun `scanner finds field resolver method using capitalize field_name`() {
        val resolver = RootResolverInfo(listOf(CapitalizeQuery()), options)

        val meta = scanner.findFieldResolver(FieldDefinition("id", TypeName("HullType")), resolver)

        assert(meta is MethodFieldResolver)
        assertEquals((meta as MethodFieldResolver).method.returnType, HullType::class.java)
    }

    class RootQuery1 : GraphQLQueryResolver {
        fun field1() {}
    }

    class RootQuery2 : GraphQLQueryResolver {
        fun field2() {}
    }

    class DuplicateQuery : GraphQLQueryResolver {
        fun field1() {}
    }

    class CamelCaseQuery1 : GraphQLQueryResolver {
        fun getHullType(): HullType = HullType()
    }

    class CapitalizeQuery : GraphQLQueryResolver {
        fun getId(): HullType = HullType()
    }

    class HullType

    open class ParentPropertyQuery {
        private var version: Int = 1
    }

    class PropertyQuery : ParentPropertyQuery(), GraphQLQueryResolver {
        private var name: String = "name"
    }

    class User

    class GenericQuery : GraphQLQueryResolver {
        fun getUsers(): Connection<User> {
            return DefaultConnection(listOf(), DefaultPageInfo(null, null, false, false))
        }
    }

    abstract class Boat : Vehicle {
        override fun getInformation(): BoatInformation = this.getInformation()
    }

    class BoatInformation : VehicleInformation

    class Kayak : Boat()
}
