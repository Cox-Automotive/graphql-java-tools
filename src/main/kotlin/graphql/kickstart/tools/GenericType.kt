package graphql.kickstart.tools

import com.fasterxml.classmate.ResolvedType
import graphql.kickstart.tools.util.JavaType
import graphql.kickstart.tools.util.ParameterizedTypeImpl
import graphql.kickstart.tools.util.Primitives
import graphql.kickstart.tools.util.unwrap
import org.apache.commons.lang3.reflect.TypeUtils
import java.lang.reflect.ParameterizedType
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType

/**
 * @author Andrew Potter
 */
internal open class GenericType(protected val mostSpecificType: JavaType, protected val options: SchemaParserOptions) {

    fun isTypeAssignableFromRawClass(type: ParameterizedType, clazz: Class<*>) =
        clazz.isAssignableFrom(getRawClass(type.rawType))

    fun getRawClass() = getRawClass(mostSpecificType)

    fun getRawClass(type: JavaType): Class<*> = TypeUtils.getRawType(type, mostSpecificType)

    fun isAssignableFrom(type: JavaType) = TypeUtils.isAssignable(type, mostSpecificType)

    fun relativeToPotentialParent(declaringType: JavaType): RelativeTo {
        if (declaringType !is Class<*> || declaringType.isInterface) {
            return relativeToType(declaringType)
        }

        val type = getGenericSuperType(mostSpecificType, declaringType)
        if (type == null) {
            error("Unable to find generic type of class ${TypeUtils.toString(declaringType)} relative to ${TypeUtils.toString(mostSpecificType)}")
        } else {
            return relativeToType(type)
        }
    }

    fun relativeToType(declaringType: JavaType) = RelativeTo(declaringType, mostSpecificType, options)

    fun getGenericInterface(targetInterface: Class<*>) = getGenericInterface(mostSpecificType, targetInterface)

    private fun getGenericInterface(type: JavaType?, targetInterface: Class<*>): JavaType? {
        if (type == null) {
            return null
        }

        val raw = type as? Class<*> ?: getRawClass(type)

        if (raw == targetInterface) {
            return type
        }

        val possibleSubInterface = raw.genericInterfaces.find { genericInterface ->
            TypeUtils.isAssignable(genericInterface, targetInterface)
        } ?: raw.interfaces.find { iface ->
            TypeUtils.isAssignable(iface, targetInterface)
        } ?: getGenericInterface(raw.genericSuperclass, targetInterface) ?: return null

        return getGenericInterface(possibleSubInterface, targetInterface)
    }

    fun getGenericSuperType(targetSuperClass: Class<*>) = getGenericSuperType(mostSpecificType, targetSuperClass)

    private fun getGenericSuperType(type: JavaType?, targetSuperClass: Class<*>): JavaType? {
        if (type == null) {
            return null
        }

        val raw = type as? Class<*> ?: TypeUtils.getRawType(type, type)

        if (raw == targetSuperClass) {
            return type
        }

        return getGenericSuperType(raw.genericSuperclass, targetSuperClass)
    }

    class RelativeTo(private val declaringType: JavaType, mostSpecificType: JavaType, options: SchemaParserOptions) : GenericType(mostSpecificType, options) {

        /**
         * Unwrap certain Java types to find the "real" class.
         */
        fun unwrapGenericType(javaType: JavaType): JavaType {
            val type = replaceTypeVariable(javaType)
            return when (type) {
                is ParameterizedType -> {
                    val rawType = type.rawType
                    val genericType = options.genericWrappers.find { it.type == rawType }
                        ?: return type

                    val typeArguments = type.actualTypeArguments
                    if (typeArguments.size <= genericType.index) {
                        throw IndexOutOfBoundsException("Generic type '${TypeUtils.toString(type)}' does not have a type argument at index ${genericType.index}!")
                    }

                    val unwrapsTo = genericType.schemaWrapper.invoke(typeArguments[genericType.index])
                    return unwrapGenericType(unwrapsTo)
                }
                is TypeVariable<*> -> {
                    val parameterizedDeclaringType = parameterizedDeclaringTypeOrSuperType(declaringType)
                    if (parameterizedDeclaringType != null) {
                        unwrapGenericType(parameterizedDeclaringType, type)
                    } else {
                        error("Could not resolve type variable '${TypeUtils.toLongString(type)}' because declaring type is not parameterized: ${TypeUtils.toString(declaringType)}")
                    }
                }
                is WildcardType -> type.upperBounds.firstOrNull()
                    ?: throw error("Unable to unwrap type, wildcard has no upper bound: $type")
                is Class<*> -> if (type.isPrimitive) Primitives.wrap(type) else type
                else -> error("Unable to unwrap type: $type")
            }
        }

        private fun parameterizedDeclaringTypeOrSuperType(declaringType: JavaType): ParameterizedType? =
            if (declaringType is ParameterizedType) {
                declaringType
            } else {
                val superclass = declaringType.unwrap().genericSuperclass
                if (superclass != null) {
                    parameterizedDeclaringTypeOrSuperType(superclass)
                } else {
                    null
                }
            }

        private fun unwrapGenericType(declaringType: ParameterizedType, type: TypeVariable<*>): JavaType {
            val rawClass = getRawClass(mostSpecificType)
            val arguments = TypeUtils.determineTypeArguments(rawClass, declaringType)
            val matchingType = arguments
                .filter { it.key.name == type.name }
                .values
                .firstOrNull()
                ?: error("No type variable found for: ${TypeUtils.toLongString(type)}")

            return unwrapGenericType(matchingType)
        }

        private fun replaceTypeVariable(type: JavaType): JavaType {
            return when (type) {
                is ParameterizedType -> {
                    val actualTypeArguments = type.actualTypeArguments.map { replaceTypeVariable(it) }.toTypedArray()
                    ParameterizedTypeImpl.make(type.rawType as Class<*>?, actualTypeArguments, type.ownerType)
                }
                is ResolvedType -> {
                    if (type.typeParameters.isEmpty()) {
                        type.erasedType
                    } else {
                        val actualTypeArguments = type.typeParameters.map { replaceTypeVariable(it) }.toTypedArray()
                        ParameterizedTypeImpl.make(type.erasedType, actualTypeArguments, null)
                    }
                }
                is TypeVariable<*> -> {
                    if (declaringType is ParameterizedType) {
                        extractFromDeclaringType(type, declaringType)
                    } else {
                        type
                    }
                }
                else -> {
                    type
                }
            }
        }

        private fun extractFromDeclaringType(type: TypeVariable<*>, declaringType: ParameterizedType): JavaType {
            val genericDeclaration: Any = type.genericDeclaration
            val typeVarAssigns = TypeUtils.getTypeArguments(declaringType, genericDeclaration as Class<*>)
            return typeVarAssigns[type] ?: TypeUtils.getRawType(type, declaringType)
        }
    }
}
