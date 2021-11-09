package com.taxonic.carml.engine.rdf;

import com.taxonic.carml.engine.LogicalSourcePipeline;
import com.taxonic.carml.engine.RefObjectMapper;
import com.taxonic.carml.engine.RmlMapper;
import com.taxonic.carml.engine.RmlMapperException;
import com.taxonic.carml.engine.TermGeneratorFactory;
import com.taxonic.carml.engine.function.Functions;
import com.taxonic.carml.engine.reactivedev.join.ChildSideJoinStoreProvider;
import com.taxonic.carml.engine.reactivedev.join.ParentSideJoinConditionStoreProvider;
import com.taxonic.carml.engine.reactivedev.join.impl.CarmlChildSideJoinStoreProvider;
import com.taxonic.carml.engine.reactivedev.join.impl.CarmlParentSideJoinConditionStoreProvider;
import com.taxonic.carml.engine.sourceresolver.ClassPathResolver;
import com.taxonic.carml.engine.sourceresolver.CompositeSourceResolver;
import com.taxonic.carml.engine.sourceresolver.FileResolver;
import com.taxonic.carml.engine.sourceresolver.SourceResolver;
import com.taxonic.carml.engine.template.TemplateParser;
import com.taxonic.carml.logicalsourceresolver.LogicalSourceResolver;
import com.taxonic.carml.model.LogicalSource;
import com.taxonic.carml.model.RefObjectMap;
import com.taxonic.carml.model.TriplesMap;
import com.taxonic.carml.util.Mapping;
import java.io.InputStream;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.util.ModelCollector;
import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Flux;

@Slf4j
public class RdfRmlMapper extends RmlMapper<Statement> {

  private static final long SECONDS_TO_TIMEOUT = 30;

  private final RdfMapperOptions rdfMapperOptions;

  private final Supplier<ValueFactory> valueFactorySupplier;

  private final TermGeneratorFactory<Value> termGeneratorFactory;

  private final Map<IRI, Supplier<LogicalSourceResolver<?>>> logicalSourceResolverSuppliers;

  private final ChildSideJoinStoreProvider<Resource, IRI> childSideJoinCacheProvider;

  private final ParentSideJoinConditionStoreProvider<Resource> parentSideJoinConditionStoreProvider;

  private RdfRmlMapper(Set<TriplesMap> triplesMaps, Function<Object, Optional<Flux<DataBuffer>>> sourceResolver,
      Map<TriplesMap, LogicalSourcePipeline<?, Statement>> logicalSourcePipelinePool,
      Map<? extends RefObjectMapper<Statement>, TriplesMap> refObjectMapperToParentTriplesMap,
      RdfMapperOptions rdfMapperOptions, Supplier<ValueFactory> valueFactorySupplier,
      TermGeneratorFactory<Value> termGeneratorFactory,
      Map<IRI, Supplier<LogicalSourceResolver<?>>> logicalSourceResolverSuppliers,
      ChildSideJoinStoreProvider<Resource, IRI> childSideJoinCacheProvider,
      ParentSideJoinConditionStoreProvider<Resource> parentSideJoinConditionStoreProvider) {
    super(triplesMaps, sourceResolver, logicalSourcePipelinePool, refObjectMapperToParentTriplesMap);
    this.rdfMapperOptions = rdfMapperOptions;
    this.valueFactorySupplier = valueFactorySupplier;
    this.termGeneratorFactory = termGeneratorFactory;
    this.logicalSourceResolverSuppliers = logicalSourceResolverSuppliers;
    this.childSideJoinCacheProvider = childSideJoinCacheProvider;
    this.parentSideJoinConditionStoreProvider = parentSideJoinConditionStoreProvider;
  }

  public static Builder builder() {
    return new Builder();
  }

  @NoArgsConstructor(access = AccessLevel.PRIVATE)
  public static class Builder {
    private final Map<IRI, Supplier<LogicalSourceResolver<?>>> logicalSourceResolverSuppliers = new HashMap<>();

    private Set<TriplesMap> triplesMaps = new HashSet<>();

    private Set<TriplesMap> mappableTriplesMaps = new HashSet<>();

    private final Functions functions = new Functions();

    private final Set<SourceResolver> sourceResolvers = new HashSet<>();

    private Supplier<ValueFactory> valueFactorySupplier = SimpleValueFactory::getInstance;

    private Normalizer.Form normalizationForm = Normalizer.Form.NFC;

    private boolean iriUpperCasePercentEncoding = true;

    private TermGeneratorFactory<Value> termGeneratorFactory;

    private ChildSideJoinStoreProvider<Resource, IRI> childSideJoinCacheProvider = CarmlChildSideJoinStoreProvider.of();

    private ParentSideJoinConditionStoreProvider<Resource> parentSideJoinConditionStoreProvider =
        CarmlParentSideJoinConditionStoreProvider.of();

    public Builder addFunctions(Object... fn) {
      functions.addFunctions(fn);
      return this;
    }

    public Builder sourceResolver(SourceResolver sourceResolver) {
      sourceResolvers.add(sourceResolver);
      return this;
    }

    public Builder fileResolver(Path basePath) {
      sourceResolvers.add(FileResolver.of(basePath));
      return this;
    }

    public Builder classPathResolver(String basePath) {
      sourceResolvers.add(ClassPathResolver.of(basePath));
      return this;
    }

    public Builder classPathResolver(ClassPathResolver classPathResolver) {
      sourceResolvers.add(classPathResolver);
      return this;
    }

    public Builder setLogicalSourceResolver(IRI iri, Supplier<LogicalSourceResolver<?>> resolver) {
      logicalSourceResolverSuppliers.put(iri, resolver);
      return this;
    }

    public Builder valueFactorySupplier(Supplier<ValueFactory> valueFactorySupplier) {
      this.valueFactorySupplier = valueFactorySupplier;
      return this;
    }

    public Builder iriUnicodeNormalization(Normalizer.Form normalizationForm) {
      this.normalizationForm = normalizationForm;
      return this;
    }

    /**
     * Builder option for backwards compatibility. RmlMapper used to percent encode IRIs with lower case
     * hex numbers. Now, the default is upper case hex numbers.
     *
     * @param iriUpperCasePercentEncoding true for upper case, false for lower case
     * @return {@link Builder}
     */
    public Builder iriUpperCasePercentEncoding(boolean iriUpperCasePercentEncoding) {
      this.iriUpperCasePercentEncoding = iriUpperCasePercentEncoding;
      return this;
    }

    public Builder triplesMaps(Set<TriplesMap> triplesMaps) {
      this.triplesMaps = triplesMaps;
      this.mappableTriplesMaps = Mapping.filterMappable(triplesMaps);
      return this;
    }

    public Builder childSideJoinStoreProvider(ChildSideJoinStoreProvider<Resource, IRI> childSideJoinCacheProvider) {
      this.childSideJoinCacheProvider = childSideJoinCacheProvider;
      return this;
    }

    public Builder parentSideJoinConditionStoreProvider(
        ParentSideJoinConditionStoreProvider<Resource> parentSideJoinConditionStoreProvider) {
      this.parentSideJoinConditionStoreProvider = parentSideJoinConditionStoreProvider;
      return this;
    }

    public RdfRmlMapper build() {
      if (logicalSourceResolverSuppliers.isEmpty()) {
        throw new RmlMapperException("No logical source resolver suppliers specified.");
      }

      RdfMapperOptions mapperOptions = RdfMapperOptions.builder()
          .valueFactory(valueFactorySupplier.get())
          .normalizationForm(normalizationForm)
          .iriUpperCasePercentEncoding(iriUpperCasePercentEncoding)
          .functions(functions)
          .build();

      if (termGeneratorFactory == null) {
        termGeneratorFactory =
            RdfTermGeneratorFactory.of(mapperOptions, TemplateParser.build(), parentSideJoinConditionStoreProvider);
      }

      var rdfMappingContext = RdfMappingContext.builder()
          .valueFactorySupplier(valueFactorySupplier)
          .termGeneratorFactory(termGeneratorFactory)
          .childSideJoinStoreProvider(childSideJoinCacheProvider)
          .build();

      Map<TriplesMap, Set<RdfRefObjectMapper>> tmToRoMappers = new HashMap<>();
      Map<RdfRefObjectMapper, TriplesMap> roMapperToParentTm = new HashMap<>();

      if (mappableTriplesMaps.isEmpty()) {
        throw new RmlMapperException("No actionable triples maps provided.");
      }

      for (TriplesMap triplesMap : mappableTriplesMaps) {
        Set<RdfRefObjectMapper> roMappers = new HashSet<>();
        triplesMap.getPredicateObjectMaps()
            .stream()
            .flatMap(pom -> pom.getObjectMaps()
                .stream())
            .filter(RefObjectMap.class::isInstance)
            .map(RefObjectMap.class::cast)
            .filter(rom -> !rom.getJoinConditions()
                .isEmpty())
            .forEach(rom -> {
              var roMapper = RdfRefObjectMapper.of(rom, triplesMap, rdfMappingContext, childSideJoinCacheProvider);
              roMappers.add(roMapper);
              roMapperToParentTm.put(roMapper, rom.getParentTriplesMap());
            });
        tmToRoMappers.put(triplesMap, roMappers);
      }

      Map<LogicalSource, List<TriplesMap>> groupedTriplesMaps = mappableTriplesMaps.stream()
          .collect(Collectors.groupingBy(TriplesMap::getLogicalSource));

      Set<RdfLogicalSourcePipeline<?>> logicalSourcePipelines = groupedTriplesMaps.entrySet()
          .stream()
          .map(triplesMapGroup -> buildRdfLogicalSourcePipeline(triplesMapGroup.getKey(), triplesMapGroup.getValue(),
              tmToRoMappers, roMapperToParentTm, rdfMappingContext))
          .collect(Collectors.toSet());

      Map<TriplesMap, LogicalSourcePipeline<?, Statement>> logicalSourcePipelinePool = new HashMap<>();
      for (RdfLogicalSourcePipeline<?> logicalSourcePipeline : logicalSourcePipelines) {
        logicalSourcePipeline.getTriplesMappers()
            .forEach(rdfTriplesMapper -> logicalSourcePipelinePool.put(rdfTriplesMapper.getTriplesMap(),
                logicalSourcePipeline));
      }

      var compositeResolver = CompositeSourceResolver.of(Set.copyOf(sourceResolvers));

      return new RdfRmlMapper(triplesMaps, compositeResolver, logicalSourcePipelinePool, roMapperToParentTm,
          mapperOptions, valueFactorySupplier, termGeneratorFactory, logicalSourceResolverSuppliers,
          childSideJoinCacheProvider, parentSideJoinConditionStoreProvider);
    }

    private RdfLogicalSourcePipeline<?> buildRdfLogicalSourcePipeline(LogicalSource logicalSource,
        List<TriplesMap> triplesMaps, Map<TriplesMap, Set<RdfRefObjectMapper>> tmToRoMappers,
        Map<RdfRefObjectMapper, TriplesMap> roMapperToParentTm, RdfMappingContext rdfMappingContext) {

      Supplier<LogicalSourceResolver<?>> logicalSourceResolverSupplier =
          logicalSourceResolverSuppliers.get(logicalSource.getReferenceFormulation());

      if (logicalSourceResolverSupplier == null) {
        throw new RmlMapperException(
            String.format("No logical source resolver supplier bound for reference formulation %s",
                logicalSource.getReferenceFormulation()));
      }

      return RdfLogicalSourcePipeline.of(logicalSource, triplesMaps, tmToRoMappers, roMapperToParentTm,
          logicalSourceResolverSupplier.get(), rdfMappingContext, parentSideJoinConditionStoreProvider);
    }
  }

  public Model mapToModel() {
    return toModel(map());
  }

  public Model mapToModel(Set<TriplesMap> triplesMapFilter) {
    return toModel(map(triplesMapFilter));
  }

  public Model mapToModel(@NonNull InputStream inputStream) {
    return toModel(map(inputStream));
  }

  public Model mapToModel(@NonNull InputStream inputStream, Set<TriplesMap> triplesMapFilter) {
    return toModel(map(inputStream, triplesMapFilter));
  }

  public Model mapToModel(Map<String, InputStream> namedInputStreams) {
    return toModel(map(namedInputStreams));
  }

  public Model mapToModel(Map<String, InputStream> namedInputStreams, Set<TriplesMap> triplesMapFilter) {
    return toModel(map(namedInputStreams, triplesMapFilter));
  }

  public Model mapItemToModel(Object item) {
    return toModel(mapItem(item));
  }

  public Model mapItemToModel(Object item, Set<TriplesMap> triplesMapFilter) {
    return toModel(mapItem(item, triplesMapFilter));
  }

  private Model toModel(Flux<Statement> statementFlux) {
    return statementFlux.collect(ModelCollector.toModel())
        .block(Duration.ofSeconds(SECONDS_TO_TIMEOUT));
  }
}
