package com.coxautodev.graphql.tools

import graphql.language.StringValue
import graphql.schema.Coercing
import graphql.schema.GraphQLScalarType
import java.util.UUID

val schemaDefinition = """

scalar UUID

type Query {
    items(itemsInput: ItemSearchInput!): [Item!] @doc(d: "Get items by name")
    allItems: [AllItems!]
    itemsByInterface: [ItemInterface!]
    itemByUUID(uuid: UUID!): Item
}

type Mutation {
    addItem(newItem: NewItemInput!): Item!
}

input ItemSearchInput {
    name: String! @doc(d: "The item name to look for")
}

input NewItemInput {
    name: String!
    type: Type!
}

enum Type {
    TYPE_1 @doc(d: "Item type 1")
    TYPE_2 @doc(d: "Item type 2")
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

type Tag {
    id: Int!
    name: String!
}
"""


val items = mutableListOf(
    Item(0, "item1", Type.TYPE_1, UUID.fromString("38f685f1-b460-4a54-a17f-7fd69e8cf3f8"), listOf(Tag(1, "item1-tag1"), Tag(2, "item1-tag2"))),
    Item(1, "item2", Type.TYPE_2, UUID.fromString("38f685f1-b460-4a54-b17f-7fd69e8cf3f8"), listOf(Tag(3, "item2-tag1"), Tag(4, "item2-tag2")))
)

val otherItems = mutableListOf(
    OtherItem(0, "otherItem1", Type.TYPE_1, UUID.fromString("38f685f1-b460-4a54-c17f-7fd69e8cf3f8")),
    OtherItem(1, "otherItem2", Type.TYPE_2, UUID.fromString("38f685f1-b460-4a54-d17f-7fd69e8cf3f8"))
)

class Query : GraphQLRootResolver() {
    fun items(input: ItemSearchInput): List<Item> = items.filter { it.name == input.name }
    fun allItems(): List<Any> = items + otherItems
    fun itemsByInterface(): List<ItemInterface> = items + otherItems
    fun itemByUUID(uuid: UUID): Item? = items.find { it.uuid == uuid }
}

class Mutation: GraphQLRootResolver() {
    fun addItem(input: NewItemInput): Item {
        return Item(items.size, input.name, input.type, UUID.randomUUID(), listOf()).apply {
            items.add(this)
        }
    }
}

class ItemResolver : GraphQLResolver(Item::class.java) {
    fun tags(item: Item, names: List<String>?): List<Tag> = item.tags.filter { names?.contains(it.name) ?: true }
}

interface ItemInterface {
    val name: String
    val type: Type
    val uuid: UUID
}

enum class Type { TYPE_1, TYPE_2 }
data class Item(val id: Int, override val name: String, override val type: Type, override val uuid:UUID, val tags: List<Tag>) : ItemInterface
data class OtherItem(val id: Int, override val name: String, override val type: Type, override val uuid:UUID) : ItemInterface
data class Tag(val id: Int, val name: String)
data class ItemSearchInput(val name: String)
data class NewItemInput(val name: String, val type: Type)

val CustomUUIDScalar = GraphQLScalarType("UUID", "UUID", object : Coercing {

    override fun parseValue(input: Any?): Any? = serialize(input)

    override fun parseLiteral(input: Any?): Any? = when (input) {
        is StringValue -> UUID.fromString(input.value)
        else -> null
    }

    override fun serialize(input: Any?): Any? = when (input) {
        is String -> UUID.fromString(input)
        is UUID -> input
        else -> null
    }
})
