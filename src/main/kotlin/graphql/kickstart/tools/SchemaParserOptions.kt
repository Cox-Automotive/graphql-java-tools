package graphql.kickstart.tools

import com.fasterxml.jackson.databind.ObjectMapper
import graphql.kickstart.tools.proxy.*
import graphql.kickstart.tools.relay.RelayConnectionFactory
import graphql.kickstart.tools.util.JavaType
import graphql.kickstart.tools.util.ParameterizedTypeImpl
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.visibility.GraphqlFieldVisibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.reactive.publish
import org.reactivestreams.Publisher
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Future
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

data class SchemaParserOptions internal constructor(
    val contextClass: Class<*>?,
    val genericWrappers: List<GenericWrapper>,
    val allowUnimplementedResolvers: Boolean,
    val missingResolverDataFetcher: DataFetcher<Any?>?,
    val objectMapperProvider: PerFieldObjectMapperProvider,
    val proxyHandlers: List<ProxyHandler>,
    val inputArgumentOptionalDetectOmission: Boolean,
    val preferGraphQLResolver: Boolean,
    val introspectionEnabled: Boolean,
    val coroutineContextProvider: CoroutineContextProvider,
    val typeDefinitionFactories: List<TypeDefinitionFactory>,
    val fieldVisibility: GraphqlFieldVisibility?,
    val includeUnusedTypes: Boolean,
    val useCommentsForDescriptions: Boolean
) {
    companion object {
        @JvmStatic
        fun newOptions() = Builder()

        @JvmStatic
        @ExperimentalCoroutinesApi
        fun defaultOptions() = Builder().build()
    }

    val coroutineContext: CoroutineContext
        get() = coroutineContextProvider.provide()

    class Builder {
        private var contextClass: Class<*>? = null
        private val genericWrappers: MutableList<GenericWrapper> = mutableListOf()
        private var useDefaultGenericWrappers = true
        private var allowUnimplementedResolvers = false
        private var missingResolverDataFetcher: DataFetcher<Any?>? = null
        private var objectMapperProvider: PerFieldObjectMapperProvider = PerFieldConfiguringObjectMapperProvider()
        private val proxyHandlers: MutableList<ProxyHandler> = mutableListOf(Spring4AopProxyHandler(), GuiceAopProxyHandler(), JavassistProxyHandler(), WeldProxyHandler())
        private var inputArgumentOptionalDetectOmission = false
        private var preferGraphQLResolver = false
        private var introspectionEnabled = true
        private var coroutineContextProvider: CoroutineContextProvider? = null
        private var typeDefinitionFactories: MutableList<TypeDefinitionFactory> = mutableListOf(RelayConnectionFactory())
        private var fieldVisibility: GraphqlFieldVisibility? = null
        private var includeUnusedTypes = false
        private var useCommentsForDescriptions = true

        fun contextClass(contextClass: Class<*>) = this.apply {
            this.contextClass = contextClass
        }

        fun contextClass(contextClass: KClass<*>) = this.apply {
            this.contextClass = contextClass.java
        }

        fun genericWrappers(genericWrappers: List<GenericWrapper>) = this.apply {
            this.genericWrappers.addAll(genericWrappers)
        }

        fun genericWrappers(vararg genericWrappers: GenericWrapper) = this.apply {
            this.genericWrappers.addAll(genericWrappers)
        }

        fun useDefaultGenericWrappers(useDefaultGenericWrappers: Boolean) = this.apply {
            this.useDefaultGenericWrappers = useDefaultGenericWrappers
        }

        fun allowUnimplementedResolvers(allowUnimplementedResolvers: Boolean) = this.apply {
            this.allowUnimplementedResolvers = allowUnimplementedResolvers
        }

        fun missingResolverDataFetcher(missingResolverDataFetcher: DataFetcher<Any?>?) = this.apply {
            this.missingResolverDataFetcher = missingResolverDataFetcher
        }

        fun inputArgumentOptionalDetectOmission(inputArgumentOptionalDetectOmission: Boolean) = this.apply {
            this.inputArgumentOptionalDetectOmission = inputArgumentOptionalDetectOmission
        }

        fun preferGraphQLResolver(preferGraphQLResolver: Boolean) = this.apply {
            this.preferGraphQLResolver = preferGraphQLResolver
        }

        fun objectMapperConfigurer(objectMapperConfigurer: ObjectMapperConfigurer) = this.apply {
            this.objectMapperProvider = PerFieldConfiguringObjectMapperProvider(objectMapperConfigurer)
        }

        fun objectMapperProvider(objectMapperProvider: PerFieldObjectMapperProvider) = this.apply {
            this.objectMapperProvider = objectMapperProvider
        }

        fun objectMapperConfigurer(objectMapperConfigurer: (ObjectMapper, ObjectMapperConfigurerContext) -> Unit) = this.apply {
            this.objectMapperConfigurer(ObjectMapperConfigurer(objectMapperConfigurer))
        }

        fun addProxyHandler(proxyHandler: ProxyHandler) = this.apply {
            this.proxyHandlers.add(proxyHandler)
        }

        fun introspectionEnabled(introspectionEnabled: Boolean) = this.apply {
            this.introspectionEnabled = introspectionEnabled
        }

        fun coroutineContext(context: CoroutineContext) = this.apply {
            this.coroutineContextProvider = DefaultCoroutineContextProvider(context)
        }

        fun coroutineContextProvider(contextProvider: CoroutineContextProvider) = this.apply {
            this.coroutineContextProvider = contextProvider
        }

        fun typeDefinitionFactory(factory: TypeDefinitionFactory) = this.apply {
            this.typeDefinitionFactories.add(factory)
        }

        fun fieldVisibility(fieldVisibility: GraphqlFieldVisibility) = this.apply {
            this.fieldVisibility = fieldVisibility
        }

        fun includeUnusedTypes(includeUnusedTypes: Boolean) = this.apply {
            this.includeUnusedTypes = includeUnusedTypes
        }

        fun useCommentsForDescriptions(useCommentsForDescriptions: Boolean) = this.apply {
            this.useCommentsForDescriptions = useCommentsForDescriptions
        }

        @ExperimentalCoroutinesApi
        fun build(): SchemaParserOptions {
            val coroutineContextProvider = coroutineContextProvider
                ?: DefaultCoroutineContextProvider(Dispatchers.Default)
            val wrappers = if (useDefaultGenericWrappers) {
                genericWrappers + listOf(
                    GenericWrapper(Future::class, 0),
                    GenericWrapper(CompletableFuture::class, 0),
                    GenericWrapper(CompletionStage::class, 0),
                    GenericWrapper(Publisher::class, 0),
                    GenericWrapper.withTransformer(ReceiveChannel::class, 0, { receiveChannel, _ ->
                        publish(coroutineContextProvider.provide()) {
                            try {
                                for (item in receiveChannel) {
                                    send(item)
                                }
                            } finally {
                                receiveChannel.cancel()
                            }
                        }
                    })
                )
            } else {
                genericWrappers
            }

            return SchemaParserOptions(
                contextClass,
                wrappers,
                allowUnimplementedResolvers,
                missingResolverDataFetcher,
                objectMapperProvider,
                proxyHandlers,
                inputArgumentOptionalDetectOmission,
                preferGraphQLResolver,
                introspectionEnabled,
                coroutineContextProvider,
                typeDefinitionFactories,
                fieldVisibility,
                includeUnusedTypes,
                useCommentsForDescriptions
            )
        }
    }

    internal class DefaultCoroutineContextProvider(private val coroutineContext: CoroutineContext) : CoroutineContextProvider {
        override fun provide(): CoroutineContext {
            return coroutineContext
        }
    }

    data class GenericWrapper(
        val type: Class<*>,
        val index: Int,
        val transformer: (Any, DataFetchingEnvironment) -> Any? = { x, _ -> x },
        val schemaWrapper: (JavaType) -> JavaType = { x -> x }
    ) {
        constructor(type: Class<*>, index: Int) : this(type, index, { x, _ -> x })
        constructor(type: KClass<*>, index: Int) : this(type.java, index, { x, _ -> x })

        companion object {
            @Suppress("UNCHECKED_CAST")
            @JvmStatic
            fun <T> withTransformer(
                type: Class<T>,
                index: Int,
                transformer: (T, DataFetchingEnvironment) -> Any?,
                schemaWrapper: (JavaType) -> JavaType = { x -> x }
            ): GenericWrapper where T : Any {
                return GenericWrapper(type, index, transformer as (Any, DataFetchingEnvironment) -> Any?, schemaWrapper)
            }

            fun <T> withTransformer(
                type: KClass<T>,
                index: Int,
                transformer: (T, DataFetchingEnvironment) -> Any?,
                schemaWrapper: (JavaType) -> JavaType = { x -> x }
            ): GenericWrapper where T : Any {
                return withTransformer(type.java, index, transformer, schemaWrapper)
            }

            @JvmStatic
            fun <T> withTransformer(
                type: Class<T>,
                index: Int,
                transformer: (T) -> Any?,
                schemaWrapper: (JavaType) -> JavaType = { x -> x }
            ): GenericWrapper where T : Any {
                return withTransformer(type, index, { x, _ -> transformer.invoke(x) }, schemaWrapper)
            }

            fun <T> withTransformer(
                type: KClass<T>,
                index: Int,
                transformer: (T) -> Any?,
                schemaWrapper: (JavaType) -> JavaType = { x -> x }
            ): GenericWrapper where T : Any {
                return withTransformer(type.java, index, transformer, schemaWrapper)
            }

            @JvmStatic
            fun <T> listCollectionWithTransformer(
                type: Class<T>,
                index: Int,
                transformer: (T) -> Any?
            ): GenericWrapper where T : Any {
                return withTransformer(
                    type,
                    index,
                    transformer,
                    { innerType -> ParameterizedTypeImpl.make(List::class.java, arrayOf(innerType), null) }
                )
            }

            fun <T> listCollectionWithTransformer(
                type: KClass<T>,
                index: Int,
                transformer: (T) -> Any?
            ): GenericWrapper where T : Any {
                return listCollectionWithTransformer(type.java, index, transformer)
            }
        }
    }
}
