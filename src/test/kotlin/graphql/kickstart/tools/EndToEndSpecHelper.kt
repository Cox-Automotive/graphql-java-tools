package graphql.kickstart.tools

import graphql.execution.DataFetcherResult
import graphql.language.ObjectValue
import graphql.language.StringValue
import graphql.relay.*
import graphql.schema.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import org.reactivestreams.Publisher
import java.io.InputStream
import java.util.*
import java.util.concurrent.CompletableFuture
import javax.servlet.http.Part

fun createSchema() = SchemaParser.newParser()
    .schemaString(schemaDefinition)
    .resolvers(Query(), Mutation(), Subscription(), ItemResolver(), UnusedRootResolver(), UnusedResolver(), EchoFilesResolver())
    .scalars(customScalarUUID, customScalarMap, customScalarId, uploadScalar)
    .dictionary("OtherItem", OtherItemWithWrongName::class)
    .dictionary("ThirdItem", ThirdItem::class)
    .dictionary("ComplexMapItem", ComplexMapItem::class)
    .dictionary("NestedComplexMapItem", NestedComplexMapItem::class)
    .build()
    .makeExecutableSchema()

private const val schemaDefinition = """
## Private comment!
scalar UUID
scalar customScalarMap
scalar Upload

type Query {
    # Check if items list is empty
    empty: Boolean!
    # Get all items
    allBaseItems: [Item!]
    # Get items by name
    items(itemsInput: ItemSearchInput!): [Item!]
    optionalItem(itemsInput: ItemSearchInput!): Item
    allItems: [AllItems!]
    otherUnionItems: [OtherUnion!]
    nestedUnionItems: [NestedUnion!]
    itemsByInterface: [ItemInterface!]
    itemByUUID(uuid: UUID!): Item
    itemByBuiltInId(id: ID!): Item
    itemsWithOptionalInput(itemsInput: ItemSearchInput): [Item!]
    itemsWithOptionalInputExplicit(itemsInput: ItemSearchInput): [Item!]
    enumInputType(type: Type!): Type!
    customScalarMapInputType(customScalarMap: customScalarMap): customScalarMap
    itemWithGenericProperties: ItemWithGenericProperties!

    defaultArgument(arg: Boolean = true): Boolean!
    defaultEnumListArgument(types: [Type] = [TYPE_1]): [Type]

    listList: [[String!]!]!
    futureItems: [Item!]!
    complexNullableType: ComplexNullable

    complexInputType(complexInput: [[ComplexInputType!]]): String!
    extendedType: ExtendedType!

    # Exercise field with get<<capitalised field name>> resolver
    itemsWithGetResolver: [Item!]

    # Check it's possible to use field names that correspond to methods on the java.lang.Object class
    class: [Item!]
    hashCode: [Item!]

    propertyHashMapItems: [PropertyHashMapItem!]
    propertyMapMissingNamePropItems: [PropertyHashMapItem!]
    propertySortedMapItems: [PropertySortedMapItem!]
    propertyMapWithComplexItems: [PropertyMapWithComplexItem!]
    propertyMapWithNestedComplexItems: [PropertyMapWithNestedComplexItem!]

    propertyField: String!
    dataFetcherResult: Item!
    dataFetcherResultItems: [Item!]!
    dataFetcherResultInGeneric: dataFetcherResultInGenericConnection!

    coroutineItems: [Item!]!

    arrayItems: [Item!]!
    
    throwsIllegalArgumentException: String
}

type dataFetcherResultInGenericConnection {
    edges: [dataFetcherResultInGenericConnectionNode!]!
}

type dataFetcherResultInGenericConnectionNode {
    node: Item
}

type ExtendedType {
    first: String!
}

extend type ExtendedType {
    second: String!
}

type ComplexNullable {
    first: String!
    second: String!
    third: String!
}

input ComplexInputType {
    first: String!
    second: [[ComplexInputTypeTwo!]]
}

input ComplexInputTypeTwo {
    first: String!
}

type Mutation {
    addItem(newItem: NewItemInput!): Item!
    echoFiles(fileParts: [Upload!]!): [String!]!
    saveUser(input: UserInput!): String
}

type Subscription {
    onItemCreated: Item!
    onItemCreatedCoroutineChannel: Item!
    onItemCreatedCoroutineChannelAndSuspendFunction: Item!
}

input UserInput {
    name: String                        
}

extend input UserInput {
    password: String
}

input ItemSearchInput {
    # The item name to look for
    name: String!
}

input NewItemInput {
    name: String! @deprecated
    type: Type! @deprecated(reason: "This is a reason")
}

enum Type {
    # Item type 1
    TYPE_1
    # Item type 2
    TYPE_2
}

type Item implements ItemInterface {
    id: Int!
    name: String!
    type: Type!
    uuid: UUID!
    tags(names: [String!]): [Tag!]
}

type OtherItem implements ItemInterface {
    id: Int!
    name: String!
    type: Type!
    uuid: UUID!
}

interface ItemInterface {
    name: String!
    type: Type!
    uuid: UUID!
}

union AllItems = Item | OtherItem

type ThirdItem {
    id: Int!
}

type PropertyHashMapItem {
    name: String
    age: Int!
}

type PropertySortedMapItem {
    name: String!
    age: Int!
}

type ComplexMapItem {
    id: Int!
}

type UndiscoveredItem {
    id: Int!
}

type NestedComplexMapItem {
    item: UndiscoveredItem
}

type PropertyMapWithNestedComplexItem {
    nested: NestedComplexMapItem!
    age: Int!
}

type PropertyMapWithComplexItem {
    nameId: ComplexMapItem!
    age: Int!
}

union OtherUnion = Item | ThirdItem

union NestedUnion = OtherUnion | OtherItem

type Tag {
    id: Int!
    name: String!
}

type ItemWithGenericProperties {
    keys: [String!]!
}
"""

val items = listOf(
    Item(0, "item1", Type.TYPE_1, UUID.fromString("38f685f1-b460-4a54-a17f-7fd69e8cf3f8"), listOf(Tag(1, "item1-tag1"), Tag(2, "item1-tag2"))),
    Item(1, "item2", Type.TYPE_2, UUID.fromString("38f685f1-b460-4a54-b17f-7fd69e8cf3f8"), listOf(Tag(3, "item2-tag1"), Tag(4, "item2-tag2")))
)

val otherItems = listOf(
    OtherItemWithWrongName(0, "otherItem1", Type.TYPE_1, UUID.fromString("38f685f1-b460-4a54-c17f-7fd69e8cf3f8")),
    OtherItemWithWrongName(1, "otherItem2", Type.TYPE_2, UUID.fromString("38f685f1-b460-4a54-d17f-7fd69e8cf3f8"))
)

val thirdItems = listOf(
    ThirdItem(100)
)

val propetyHashMapItems = listOf(
    hashMapOf<String, Any>("name" to "bob", "age" to 55)
)

val propertyMapMissingNamePropItems = listOf(
    hashMapOf<String, Any>("age" to 55)
)

val propetySortedMapItems = listOf(
    sortedMapOf("name" to "Arthur", "age" to 76),
    sortedMapOf("name" to "Jane", "age" to 28)
)

val propertyMapWithComplexItems = listOf(
    hashMapOf("nameId" to ComplexMapItem(150), "age" to 72)
)

val propertyMapWithNestedComplexItems = listOf(
    hashMapOf("nested" to NestedComplexMapItem(UndiscoveredItem(63)), "age" to 72)
)

class Query : GraphQLQueryResolver, ListListResolver<String>() {
    fun isEmpty() = items.isEmpty()
    fun allBaseItems() = items
    fun items(input: ItemSearchInput): List<Item> = items.filter { it.name == input.name }
    fun optionalItem(input: ItemSearchInput) = items(input).firstOrNull()?.let { Optional.of(it) }
        ?: Optional.empty()

    fun allItems(): List<Any> = items + otherItems
    fun otherUnionItems(): List<Any> = items + thirdItems
    fun nestedUnionItems(): List<Any> = items + otherItems + thirdItems
    fun itemsByInterface(): List<ItemInterface> = items + otherItems
    fun itemByUUID(uuid: UUID): Item? = items.find { it.uuid == uuid }
    fun itemByBuiltInId(id: UUID): Item? {
        return items.find { it.uuid == id }
    }

    fun itemsWithOptionalInput(input: ItemSearchInput?) = if (input == null) items else items(input)
    fun itemsWithOptionalInputExplicit(input: Optional<ItemSearchInput>?) = if (input?.isPresent == true) items(input.get()) else items
    fun enumInputType(type: Type) = type
    fun customScalarMapInputType(customScalarMap: Map<String, Any>) = customScalarMap
    fun itemWithGenericProperties() = ItemWithGenericProperties(listOf("A", "B"))

    fun defaultArgument(arg: Boolean) = arg
    fun defaultEnumListArgument(types: List<Type>) = types

    fun futureItems() = CompletableFuture.completedFuture(items)
    fun complexNullableType(): ComplexNullable? = null

    fun complexInputType(input: List<List<ComplexInputType>?>?) = input?.firstOrNull()?.firstOrNull()?.let { it.first == "foo" && it.second?.firstOrNull()?.firstOrNull()?.first == "bar" }
        ?: false

    fun extendedType() = ExtendedType()

    fun getItemsWithGetResolver() = items

    fun getFieldClass() = items
    fun getFieldHashCode() = items

    fun propertyHashMapItems() = propetyHashMapItems
    fun propertyMapMissingNamePropItems() = propertyMapMissingNamePropItems
    fun propertySortedMapItems() = propetySortedMapItems
    fun propertyMapWithComplexItems() = propertyMapWithComplexItems
    fun propertyMapWithNestedComplexItems() = propertyMapWithNestedComplexItems

    private val propertyField = "test"

    fun dataFetcherResult(): DataFetcherResult<Item> {
        return DataFetcherResult.newResult<Item>().data(items.first()).build()
    }

    fun dataFetcherResultItems(): List<DataFetcherResult<Item>> {
        return listOf(DataFetcherResult.newResult<Item>().data(items.first()).build())
    }

    fun dataFetcherResultInGeneric(): Connection<DataFetcherResult<Item>> {
        val cursor = DefaultConnectionCursor("cursor")

        return DefaultConnection(
            listOf(
                DefaultEdge(
                    DataFetcherResult.newResult<Item>().data(items.first()).build(),
                    cursor
                )
            ),
            DefaultPageInfo(cursor, cursor, true, true)
        )
    }

    suspend fun coroutineItems(): List<Item> = CompletableDeferred(items).await()

    fun arrayItems() = items.toTypedArray()

    fun throwsIllegalArgumentException(): String {
        throw IllegalArgumentException("Expected")
    }
}

class UnusedRootResolver : GraphQLQueryResolver
class UnusedResolver : GraphQLResolver<String>

class ExtendedType {
    fun first() = "test"
    fun second() = "test"
}

abstract class ListListResolver<out E> {
    fun listList(): List<List<E>> = listOf(listOf())
}

class Mutation : GraphQLMutationResolver {
    fun addItem(input: NewItemInput): Item {
        return Item(items.size, input.name, input.type, UUID.randomUUID(), listOf()) // Don't actually add the item to the list, since we want the test to be deterministic
    }

    fun saveUser(userInput: UserInput): String {
        return userInput.name + "/" + userInput.password
    }

    class UserInput {
        var name: String = ""
        var password: String = ""
    }
}

class OnItemCreatedContext(val newItem: Item)

class Subscription : GraphQLSubscriptionResolver {
    fun onItemCreated(env: DataFetchingEnvironment) =
        Publisher<Item> { subscriber ->
            subscriber.onNext(env.getContext<OnItemCreatedContext>().newItem)
//            subscriber.onComplete()
        }

    fun onItemCreatedCoroutineChannel(env: DataFetchingEnvironment): ReceiveChannel<Item> {
        val channel = Channel<Item>(1)
        channel.offer(env.getContext<OnItemCreatedContext>().newItem)
        return channel
    }

    suspend fun onItemCreatedCoroutineChannelAndSuspendFunction(env: DataFetchingEnvironment): ReceiveChannel<Item> {
        return coroutineScope {
            val channel = Channel<Item>(1)
            channel.offer(env.getContext<OnItemCreatedContext>().newItem)
            channel
        }
    }
}

class ItemResolver : GraphQLResolver<Item> {
    fun tags(item: Item, names: List<String>?): List<Tag> = item.tags.filter {
        names?.contains(it.name) ?: true
    }
}

class EchoFilesResolver : GraphQLMutationResolver {
    fun echoFiles(fileParts: List<Part>): List<String> = fileParts.map { String(it.inputStream.readBytes()) }
}

interface ItemInterface {
    val name: String
    val type: Type
    val uuid: UUID
}

enum class Type { TYPE_1, TYPE_2 }
data class Item(val id: Int, override val name: String, override val type: Type, override val uuid: UUID, val tags: List<Tag>) : ItemInterface
data class OtherItemWithWrongName(val id: Int, override val name: String, override val type: Type, override val uuid: UUID) : ItemInterface
data class ThirdItem(val id: Int)
data class ComplexMapItem(val id: Int)
data class UndiscoveredItem(val id: Int)
data class NestedComplexMapItem(val item: UndiscoveredItem)
data class Tag(val id: Int, val name: String)
data class ItemSearchInput(val name: String)
data class NewItemInput(val name: String, val type: Type)
data class ComplexNullable(val first: String, val second: String, val third: String)
data class ComplexInputType(val first: String, val second: List<List<ComplexInputTypeTwo>?>?)
data class ComplexInputTypeTwo(val first: String)
data class ItemWithGenericProperties(val keys: List<String>)
class MockPart(private val name: String, private val content: String) : Part {
    override fun getSubmittedFileName(): String = name
    override fun getName(): String = name
    override fun write(fileName: String?) = throw IllegalArgumentException("Not supported")
    override fun getHeader(name: String): String? = null
    override fun getSize(): Long = content.toByteArray().size.toLong()
    override fun getContentType(): String? = null
    override fun getHeaders(name: String?): Collection<String> = listOf()
    override fun getHeaderNames(): Collection<String> = listOf()
    override fun getInputStream(): InputStream = content.byteInputStream()
    override fun delete() = throw IllegalArgumentException("Not supported")
}

val customScalarId = GraphQLScalarType.newScalar()
    .name("ID")
    .description("Overrides built-in ID")
    .coercing(object : Coercing<UUID, String> {
        override fun serialize(input: Any): String? = when (input) {
            is String -> input
            is UUID -> input.toString()
            else -> null
        }

        override fun parseValue(input: Any): UUID? = parseLiteral(input)

        override fun parseLiteral(input: Any): UUID? = when (input) {
            is StringValue -> UUID.fromString(input.value)
            else -> null
        }
    })
    .build()

val customScalarUUID = GraphQLScalarType.newScalar()
    .name("UUID")
    .description("UUID")
    .coercing(object : Coercing<UUID, String> {

        override fun serialize(input: Any): String? = when (input) {
            is String -> input
            is UUID -> input.toString()
            else -> null
        }

        override fun parseValue(input: Any): UUID? = parseLiteral(input)

        override fun parseLiteral(input: Any): UUID? = when (input) {
            is StringValue -> UUID.fromString(input.value)
            else -> null
        }
    })
    .build()

val customScalarMap = GraphQLScalarType.newScalar()
    .name("customScalarMap")
    .description("customScalarMap")
    .coercing(object : Coercing<Map<String, Any>, Map<String, Any>> {

        @Suppress("UNCHECKED_CAST")
        override fun parseValue(input: Any?): Map<String, Any> = input as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        override fun serialize(dataFetcherResult: Any?): Map<String, Any> = dataFetcherResult as Map<String, Any>

        override fun parseLiteral(input: Any?): Map<String, Any> = (input as ObjectValue).objectFields.associateBy { it.name }.mapValues { (it.value.value as StringValue).value }
    })
    .build()

// definition from https://github.com/graphql-java-kickstart/graphql-java-servlet/blob/master/src/main/java/graphql/servlet/core/ApolloScalars.java
val uploadScalar: GraphQLScalarType = GraphQLScalarType.newScalar()
    .name("Upload")
    .description("A file part in a multipart request")
    .coercing(object : Coercing<Part?, Void?> {
        override fun serialize(dataFetcherResult: Any): Void? {
            throw CoercingSerializeException("Upload is an input-only type")
        }

        override fun parseValue(input: Any?): Part? {
            return when (input) {
                is Part -> {
                    input
                }
                null -> {
                    null
                }
                else -> {
                    throw CoercingParseValueException("Expected type ${Part::class.java.name} but was ${input.javaClass.name}")
                }
            }
        }

        override fun parseLiteral(input: Any): Part? {
            throw CoercingParseLiteralException(
                "Must use variables to specify Upload values")
        }
    }).build()

