package graphql.kickstart.tools.resolver

import graphql.Scalars
import graphql.kickstart.tools.ResolverInfo
import graphql.kickstart.tools.RootResolverInfo
import graphql.kickstart.tools.SchemaParserOptions
import graphql.kickstart.tools.util.*
import graphql.language.FieldDefinition
import graphql.language.TypeName
import graphql.schema.DataFetchingEnvironment
import org.apache.commons.lang3.ClassUtils
import org.apache.commons.lang3.reflect.FieldUtils
import org.slf4j.LoggerFactory
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Type
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.javaType
import kotlin.reflect.jvm.kotlinFunction

/**
 * @author Andrew Potter
 */
internal class FieldResolverScanner(val options: SchemaParserOptions) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val allowedLastArgumentTypes = listOfNotNull(DataFetchingEnvironment::class.java, options.contextClass)

    fun findFieldResolver(field: FieldDefinition, resolverInfo: ResolverInfo): FieldResolver {
        val searches = resolverInfo.getFieldSearches()

        val scanProperties = field.inputValueDefinitions.isEmpty()
        val found = searches.mapNotNull { search -> findFieldResolver(field, search, scanProperties) }

        if (resolverInfo is RootResolverInfo && found.size > 1) {
            throw FieldResolverError("Found more than one matching resolver for field '$field': $found")
        }

        return found.firstOrNull() ?: missingFieldResolver(field, searches, scanProperties)
    }

    private fun findFieldResolver(field: FieldDefinition, search: Search, scanProperties: Boolean): FieldResolver? {
        val method = findResolverMethod(field, search)
        if (method != null) {
            return MethodFieldResolver(field, search, options, method.apply { isAccessible = true })
        }

        if (scanProperties) {
            val property = findResolverProperty(field, search)
            if (property != null) {
                return PropertyFieldResolver(field, search, options, property.apply { isAccessible = true })
            }
        }

        if (java.util.Map::class.java.isAssignableFrom(search.type.unwrap())) {
            return MapFieldResolver(field, search, options, search.type.unwrap())
        }

        return null
    }

    private fun missingFieldResolver(field: FieldDefinition, searches: List<Search>, scanProperties: Boolean): FieldResolver {
        return if (options.allowUnimplementedResolvers || options.missingResolverDataFetcher != null) {
            if (options.allowUnimplementedResolvers) {
                log.warn("Missing resolver for field: $field")
            }

            MissingFieldResolver(field, options)
        } else {
            throw FieldResolverError(getMissingFieldMessage(field, searches, scanProperties))
        }
    }

    private fun findResolverMethod(field: FieldDefinition, search: Search): java.lang.reflect.Method? {
        val methods = getAllMethods(search.type)
        val argumentCount = field.inputValueDefinitions.size + if (search.requiredFirstParameterType != null) 1 else 0
        val name = field.name

        // Check for the following one by one:
        //   1. Method with exact field name
        //   2. Method that returns a boolean with "is" style getter
        //   3. Method with "get" style getter
        //   4. Method with "getField" style getter
        //   5. Method with "get" style getter with the field name converted from snake_case to camelCased. ex: key_ops -> getKeyOps()
        return methods.find {
            it.name == name && verifyMethodArguments(it, argumentCount, search)
        } ?: methods.find {
            (isBoolean(field.type) && it.name == "is${name.replaceFirstChar(Char::titlecase)}") && verifyMethodArguments(it, argumentCount, search)
        } ?: methods.find {
            it.name == "get${name.replaceFirstChar(Char::titlecase)}" && verifyMethodArguments(it, argumentCount, search)
        } ?: methods.find {
            it.name == "getField${name.replaceFirstChar(Char::titlecase)}" && verifyMethodArguments(it, argumentCount, search)
        } ?: methods.find {
            it.name == "get${name.snakeToCamelCase()}" && verifyMethodArguments(it, argumentCount, search)
        }
    }

    private fun getAllMethods(type: JavaType): List<Method> {
        val declaredMethods = type.unwrap().declaredNonProxyMethods
        val superClassesMethods = ClassUtils.getAllSuperclasses(type.unwrap()).flatMap { it.methods.toList() }
        val interfacesMethods = ClassUtils.getAllInterfaces(type.unwrap()).flatMap { it.methods.toList() }

        return (declaredMethods + superClassesMethods + interfacesMethods)
            .asSequence()
            .filter { !it.isSynthetic }
            .filter { !Modifier.isPrivate(it.modifiers) }
            // discard any methods that are coming off the root of the class hierarchy
            // to avoid issues with duplicate method declarations
            .filter { it.declaringClass != Object::class.java }
            .toList()
    }

    private fun isBoolean(type: GraphQLLangType) = type.unwrap().let { it is TypeName && it.name == Scalars.GraphQLBoolean.name }

    private fun verifyMethodArguments(method: java.lang.reflect.Method, requiredCount: Int, search: Search): Boolean {
        val appropriateFirstParameter = if (search.requiredFirstParameterType != null) {
            method.genericParameterTypes.firstOrNull()?.let {
                it == search.requiredFirstParameterType || method.declaringClass.typeParameters.contains(it)
            } ?: false
        } else {
            true
        }

        val methodParameterCount = getMethodParameterCount(method)
        val methodLastParameter = getMethodLastParameter(method)

        val correctParameterCount = methodParameterCount == requiredCount ||
            (methodParameterCount == (requiredCount + 1) && allowedLastArgumentTypes.contains(methodLastParameter))
        return correctParameterCount && appropriateFirstParameter
    }

    private fun getMethodParameterCount(method: java.lang.reflect.Method): Int {
        return try {
            method.kotlinFunction?.valueParameters?.size ?: method.parameterCount
        } catch (e: InternalError) {
            method.parameterCount
        }
    }

    private fun getMethodLastParameter(method: java.lang.reflect.Method): Type? {
        return try {
            method.kotlinFunction?.valueParameters?.lastOrNull()?.type?.javaType
                ?: method.parameterTypes.lastOrNull()
        } catch (e: InternalError) {
            method.parameterTypes.lastOrNull()
        }
    }

    private fun findResolverProperty(field: FieldDefinition, search: Search) =
        FieldUtils.getAllFields(search.type.unwrap()).find { it.name == field.name }

    private fun getMissingFieldMessage(field: FieldDefinition, searches: List<Search>, scannedProperties: Boolean): String {
        val signatures = mutableListOf("")
        val isBoolean = isBoolean(field.type)

        searches.forEach { search ->
            signatures.addAll(getMissingMethodSignatures(field, search, isBoolean, scannedProperties))
        }

        val sourceName = if (field.sourceLocation != null && field.sourceLocation.sourceName != null) field.sourceLocation.sourceName else "<unknown>"
        val sourceLocation = if (field.sourceLocation != null) "$sourceName:${field.sourceLocation.line}" else "<unknown>"
        return "No method${if (scannedProperties) " or field" else ""} found as defined in schema $sourceLocation with any of the following signatures (with or without one of $allowedLastArgumentTypes as the last argument), in priority order:\n${signatures.joinToString("\n  ")}"
    }

    private fun getMissingMethodSignatures(field: FieldDefinition, search: Search, isBoolean: Boolean, scannedProperties: Boolean): List<String> {
        val baseType = search.type.unwrap()
        val signatures = mutableListOf<String>()
        val args = mutableListOf<String>()
        val sep = ", "

        if (search.requiredFirstParameterType != null) {
            args.add(search.requiredFirstParameterType.name)
        }

        args.addAll(field.inputValueDefinitions.map { "~${it.name}" })

        val argString = args.joinToString(sep)

        signatures.add("${baseType.name}.${field.name}($argString)")
        if (isBoolean) {
            signatures.add("${baseType.name}.is${field.name.replaceFirstChar(Char::titlecase)}($argString)")
        }
        signatures.add("${baseType.name}.get${field.name.replaceFirstChar(Char::titlecase)}($argString)")
        if (scannedProperties) {
            signatures.add("${baseType.name}.${field.name}")
        }

        return signatures
    }

    data class Search(
        val type: JavaType,
        val resolverInfo: ResolverInfo,
        val source: Any?,
        val requiredFirstParameterType: Class<*>? = null
    )
}

internal class FieldResolverError(msg: String) : RuntimeException(msg)
