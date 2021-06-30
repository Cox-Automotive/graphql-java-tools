package graphql.kickstart.tools.util

import graphql.kickstart.tools.GraphQLResolver
import graphql.kickstart.tools.SchemaParserOptions
import graphql.language.*
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy

/**
 * @author Andrew Potter
 */

internal typealias GraphQLRootResolver = GraphQLResolver<Void>

internal typealias JavaType = java.lang.reflect.Type
internal typealias JavaMethod = Method
internal typealias GraphQLLangType = Type<*>

internal fun Type<*>.unwrap(): Type<*> = when (this) {
    is NonNullType -> this.type.unwrap()
    is ListType -> this.type.unwrap()
    else -> this
}

internal fun ObjectTypeDefinition.getExtendedFieldDefinitions(extensions: List<ObjectTypeExtensionDefinition>): List<FieldDefinition> {
    return this.fieldDefinitions + extensions.filter { it.name == this.name }.flatMap { it.fieldDefinitions }
}

internal fun JavaType.unwrap(): Class<out Any> =
    if (this is ParameterizedType) {
        this.rawType as Class<*>
    } else {
        this as Class<*>
    }

internal fun DataFetchingEnvironment.coroutineScope(): CoroutineScope {
    val context: Any? = this.getContext()
    return if (context is CoroutineScope) context else CoroutineScope(Dispatchers.Default)
}

internal val Class<*>.declaredNonProxyMethods: List<JavaMethod>
    get() {
        return when {
            Proxy.isProxyClass(this) -> emptyList()
            else -> this.declaredMethods.toList()
        }
    }

internal fun getDocumentation(node: AbstractDescribedNode<*>, options: SchemaParserOptions): String? =
    when {
        node.description != null -> node.description.content
        !options.useCommentsForDescriptions -> null
        node.comments.isNullOrEmpty() -> null
        else -> node.comments.asSequence()
            .filter { !it.content.startsWith("#") }
            .joinToString("\n") { it.content.trimEnd() }
            .trimIndent()
    }

/**
 * Simple heuristic to check is a method is a trivial data fetcher.
 *
 * Requirements are:
 * prefixed with get
 * must have zero parameters
 */
internal fun isTrivialDataFetcher(method: Method): Boolean {
    return (method.parameterCount == 0
        && (
        method.name.startsWith("get")
            || isBooleanGetter(method)))
}

private fun isBooleanGetter(method: Method) = (method.name.startsWith("is")
    && (method.returnType == java.lang.Boolean::class.java)
    || method.returnType == Boolean::class.java)

internal fun String.snakeToCamelCase(): String = split("_").joinToString(separator = "") { it.capitalize() }

