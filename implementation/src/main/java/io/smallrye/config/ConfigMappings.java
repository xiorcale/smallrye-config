package io.smallrye.config;

import static io.smallrye.config.ConfigMappingLoader.getConfigMappingClass;
import static io.smallrye.config.SmallRyeConfig.SMALLRYE_CONFIG_MAPPING_VALIDATE_UNKNOWN;
import static java.lang.Boolean.TRUE;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.microprofile.config.inject.ConfigProperties;

import io.smallrye.config.ConfigMappingInterface.Property;

public final class ConfigMappings implements Serializable {
    private static final long serialVersionUID = -7790784345796818526L;

    private final ConfigValidator configValidator;
    private final ConcurrentMap<Class<?>, Map<String, ConfigMappingObject>> mappings;

    ConfigMappings(final ConfigValidator configValidator) {
        this.configValidator = configValidator;
        this.mappings = new ConcurrentHashMap<>();
    }

    public static void registerConfigMappings(final SmallRyeConfig config, final Set<ConfigClassWithPrefix> configClasses)
            throws ConfigValidationException {
        if (!configClasses.isEmpty()) {
            Boolean validateUnknown = config.getOptionalValue(SMALLRYE_CONFIG_MAPPING_VALIDATE_UNKNOWN, Boolean.class)
                    .orElse(TRUE);
            mapConfiguration(config, ConfigMappingProvider.builder().validateUnknown(validateUnknown), configClasses);
        }
    }

    public static void registerConfigProperties(final SmallRyeConfig config, final Set<ConfigClassWithPrefix> configClasses)
            throws ConfigValidationException {
        if (!configClasses.isEmpty()) {
            mapConfiguration(config, ConfigMappingProvider.builder().validateUnknown(false), configClasses);
        }
    }

    public static Map<String, Property> getProperties(final ConfigClassWithPrefix configClass) {
        ConfigMappingProvider provider = ConfigMappingProvider.builder()
                .validateUnknown(false)
                .addRoot(configClass.getPrefix(), configClass.getKlass())
                .build();

        return provider.getProperties();
    }

    public static Set<String> mappedProperties(final ConfigClassWithPrefix configClass, final Set<String> properties) {
        ConfigMappingProvider provider = ConfigMappingProvider.builder()
                .validateUnknown(false)
                .addRoot(configClass.getPrefix(), configClass.getKlass())
                .build();

        Set<String> mappedProperties = new HashSet<>();
        for (String property : properties) {
            if (provider.getMatchActions().findRootValue(new NameIterator(property)) != null) {
                mappedProperties.add(property);
            }
        }
        return mappedProperties;
    }

    static void mapConfiguration(
            final SmallRyeConfig config,
            final ConfigMappingProvider.Builder builder)
            throws ConfigValidationException {
        mapConfiguration(config, builder, new HashSet<>());
    }

    static void mapConfiguration(
            final SmallRyeConfig config,
            final ConfigMappingProvider.Builder builder,
            final Set<ConfigClassWithPrefix> configClasses)
            throws ConfigValidationException {
        for (ConfigClassWithPrefix configClass : configClasses) {
            builder.addRoot(configClass.getPrefix(), configClass.getKlass());
        }
        ConfigMappingProvider mappingProvider = builder.build();
        mapConfiguration(config, mappingProvider);
    }

    static void mapConfiguration(SmallRyeConfig config, ConfigMappingProvider mappingProvider) {
        ConfigMappingContext mappingContext = mappingProvider.mapConfiguration(config);
        config.getConfigMappings().mappings.putAll(mappingContext.getRootsMap());
    }

    <T> T getConfigMapping(Class<T> type) {
        final String prefix = Optional.ofNullable(type.getAnnotation(ConfigMapping.class))
                .map(ConfigMapping::prefix)
                .orElseGet(() -> Optional.ofNullable(type.getAnnotation(ConfigProperties.class)).map(ConfigProperties::prefix)
                        .orElse(""));

        return getConfigMapping(type, prefix);
    }

    <T> T getConfigMapping(Class<T> type, String prefix) {
        if (prefix == null) {
            return getConfigMapping(type);
        }

        final Map<String, ConfigMappingObject> mappingsForType = mappings.get(getConfigMappingClass(type));
        if (mappingsForType == null) {
            throw ConfigMessages.msg.mappingNotFound(type.getName());
        }

        final ConfigMappingObject configMappingObject = mappingsForType.get(prefix);
        if (configMappingObject == null) {
            throw ConfigMessages.msg.mappingPrefixNotFound(type.getName(), prefix);
        }

        Object value = configMappingObject;
        if (configMappingObject instanceof ConfigMappingClassMapper) {
            value = ((ConfigMappingClassMapper) configMappingObject).map();
        }

        configValidator.validateMapping(type, prefix, value);

        return type.cast(value);
    }

    static String getPrefix(Class<?> type) {
        final ConfigMapping configMapping = type.getAnnotation(ConfigMapping.class);
        return configMapping != null ? configMapping.prefix() : "";
    }

    public static final class ConfigClassWithPrefix {
        private final Class<?> klass;
        private final String prefix;

        public ConfigClassWithPrefix(final Class<?> klass, final String prefix) {
            this.klass = klass;
            this.prefix = prefix;
        }

        public Class<?> getKlass() {
            return klass;
        }

        public String getPrefix() {
            return prefix;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            final ConfigClassWithPrefix that = (ConfigClassWithPrefix) o;
            return klass.equals(that.klass) && prefix.equals(that.prefix);
        }

        @Override
        public int hashCode() {
            return Objects.hash(klass, prefix);
        }

        public static ConfigClassWithPrefix configClassWithPrefix(final Class<?> klass, final String prefix) {
            return new ConfigClassWithPrefix(klass, prefix);
        }

        public static ConfigClassWithPrefix configClassWithPrefix(final Class<?> klass) {
            return configClassWithPrefix(klass, ConfigMappings.getPrefix(klass));
        }
    }
}
