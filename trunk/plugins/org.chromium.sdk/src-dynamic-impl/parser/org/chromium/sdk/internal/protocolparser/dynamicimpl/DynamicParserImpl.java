// Copyright (c) 2009 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.sdk.internal.protocolparser.dynamicimpl;

import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.chromium.sdk.internal.protocolparser.FieldLoadStrategy;
import org.chromium.sdk.internal.protocolparser.JsonField;
import org.chromium.sdk.internal.protocolparser.JsonNullable;
import org.chromium.sdk.internal.protocolparser.JsonOptionalField;
import org.chromium.sdk.internal.protocolparser.JsonOverrideField;
import org.chromium.sdk.internal.protocolparser.JsonProtocolModelParseException;
import org.chromium.sdk.internal.protocolparser.JsonProtocolParseException;
import org.chromium.sdk.internal.protocolparser.JsonProtocolParser;
import org.chromium.sdk.internal.protocolparser.JsonSubtype;
import org.chromium.sdk.internal.protocolparser.JsonSubtypeCasting;
import org.chromium.sdk.internal.protocolparser.JsonType;
import org.chromium.sdk.internal.protocolparser.implutil.CommonImpl.ParseRuntimeException;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/**
 * Java dynamic-proxy based implementation of {@link JsonProtocolParser}. It analyses
 * interfaces with reflection and provides their implementation by {@link Proxy} factory.
 */
public class DynamicParserImpl implements JsonProtocolParser {
  private final Map<Class<?>, TypeHandler<?>> type2TypeHandler;

  /**
   * Constructs parser from a set of type interfaces.
   */
  public DynamicParserImpl(Class<?> ... protocolInterfaces)
      throws JsonProtocolModelParseException {
    this(Arrays.asList(protocolInterfaces), Collections.<DynamicParserImpl>emptyList());
  }

  /**
   * Constructs parser from a set of type interfaces and a list of base packages. Type interfaces
   * may reference to type interfaces from base packages.
   * @param basePackages list of base packages in form of list of {@link DynamicParserImpl}'s
   */
  public DynamicParserImpl(List<? extends Class<?>> protocolInterfaces,
      List<? extends DynamicParserImpl> basePackages) throws JsonProtocolModelParseException {
    this(protocolInterfaces, basePackages, false);
  }

  public DynamicParserImpl(List<? extends Class<?>> protocolInterfaces,
      List<? extends DynamicParserImpl> basePackages, boolean strictMode)
      throws JsonProtocolModelParseException {
    type2TypeHandler = readTypes(protocolInterfaces, basePackages, strictMode);
  }

  /**
   * Parses {@link JSONObject} as typeClass type.
   */
  @Override
  public <T> T parse(JSONObject object, Class<T> typeClass) throws JsonProtocolParseException {
    return parseAnything(object, typeClass);
  }

  /**
   * Parses any object as typeClass type. Non-JSONObject only makes sense for
   * types with {@link JsonType#subtypesChosenManually()} = true annotation.
   */
  @Override
  public <T> T parseAnything(Object object, Class<T> typeClass) throws JsonProtocolParseException {
    TypeHandler<T> type = type2TypeHandler.get(typeClass).cast(typeClass);
    return type.parseRoot(object);
  }

  private static Map<Class<?>, TypeHandler<?>> readTypes(
      List<? extends Class<?>> protocolInterfaces,
      final List<? extends DynamicParserImpl> basePackages, boolean strictMode)
      throws JsonProtocolModelParseException {
    ReadInterfacesSession session =
        new ReadInterfacesSession(protocolInterfaces, basePackages, strictMode);
    session.go();
    return session.getResult();
  }


  private static class ReadInterfacesSession {
    private final Map<Class<?>, TypeHandler<?>> type2typeHandler;
    private final List<? extends DynamicParserImpl> basePackages;
    private final boolean strictMode;

    final List<RefImpl<?>> refs = new ArrayList<RefImpl<?>>();
    final List<SubtypeCaster> subtypeCasters =
        new ArrayList<SubtypeCaster>();

    ReadInterfacesSession(List<? extends Class<?>> protocolInterfaces,
        List<? extends DynamicParserImpl> basePackages, boolean strictMode) {
      this.type2typeHandler = new HashMap<Class<?>, TypeHandler<?>>();
      this.basePackages = basePackages;
      this.strictMode = strictMode;

      for (Class<?> typeClass : protocolInterfaces) {
        if (type2typeHandler.containsKey(typeClass)) {
          throw new IllegalArgumentException(
              "Protocol interface duplicated " + typeClass.getName());
        }
        type2typeHandler.put(typeClass, null);
      }
    }

    void go() throws JsonProtocolModelParseException {
      // Create TypeHandler's.
      for (Class<?> typeClass : type2typeHandler.keySet()) {
        TypeHandler<?> typeHandler = createTypeHandler(typeClass);
        type2typeHandler.put(typeClass, typeHandler);
      }

      // Resolve cross-references.
      for (RefImpl<?> ref : refs) {
        TypeHandler<?> type = type2typeHandler.get(ref.typeClass);
        if (type == null) {
          throw new RuntimeException();
        }
        ref.set(type);
      }

      // Set subtype casters.
      for (SubtypeCaster subtypeCaster : subtypeCasters) {
        TypeHandler<?> subtypeHandler = subtypeCaster.getSubtypeHandler();
        subtypeHandler.getSubtypeSupport().setSubtypeCaster(subtypeCaster);
      }

      // Check subtype casters consistency.
      for (TypeHandler<?> type : type2typeHandler.values()) {
        type.getSubtypeSupport().checkHasSubtypeCaster();
      }

      if (strictMode) {
        for (TypeHandler<?> type : type2typeHandler.values()) {
          type.buildClosedNameSet();
        }
      }
    }

    Map<Class<?>, TypeHandler<?>> getResult() {
      return type2typeHandler;
    }

    private <T> TypeHandler<T> createTypeHandler(Class<T> typeClass)
        throws JsonProtocolModelParseException {
      if (!typeClass.isInterface()) {
        throw new JsonProtocolModelParseException("Json model type should be interface: " +
            typeClass.getName());
      }

      FieldProcessor<T> fields = new FieldProcessor<T>(typeClass);

      fields.go();

      Map<Method, MethodHandler> methodHandlerMap = fields.getMethodHandlerMap();
      methodHandlerMap.putAll(BaseHandlersLibrary.INSTANCE.getAllHandlers());

      TypeHandler.EagerFieldParser eagerFieldParser =
          new EagerFieldParserImpl(fields.getOnDemandHanlers());

      RefToType<?> superclassRef = getSuperclassRef(typeClass);

      return new TypeHandler<T>(typeClass, superclassRef,
          fields.getFieldArraySize(), fields.getVolatileFields(), methodHandlerMap,
          fields.getFieldLoaders(),
          fields.getFieldConditions(), eagerFieldParser, fields.getAlgCasesData(),
          strictMode);
    }

    private SlowParser<?> getFieldTypeParser(Type type, boolean declaredNullable,
        boolean isSubtyping, FieldLoadStrategy loadStrategy)
        throws JsonProtocolModelParseException {
      if (type instanceof Class) {
        Class<?> typeClass = (Class<?>) type;
        if (type == Long.class) {
          nullableIsNotSupported(declaredNullable);
          return LONG_PARSER.getNullable();
        } else if (type == Long.TYPE) {
          nullableIsNotSupported(declaredNullable);
          return LONG_PARSER.getNotNullable();
        } else if (type == Boolean.class) {
          nullableIsNotSupported(declaredNullable);
          return BOOLEAN_PARSER.getNullable();
        } else if (type == Boolean.TYPE) {
          nullableIsNotSupported(declaredNullable);
          return BOOLEAN_PARSER.getNotNullable();
        } else if (type == Float.class) {
          nullableIsNotSupported(declaredNullable);
          return FLOAT_PARSER.getNullable();
        } else if (type == Float.TYPE) {
          nullableIsNotSupported(declaredNullable);
          return FLOAT_PARSER.getNotNullable();
        } else if (type == Void.class) {
          nullableIsNotSupported(declaredNullable);
          return VOID_PARSER;
        } else if (type == String.class) {
          return STRING_PARSER.get(declaredNullable);
        } else if (type == Object.class) {
          return OBJECT_PARSER.get(declaredNullable);
        } else if (type == JSONObject.class) {
          return JSON_PARSER.get(declaredNullable);
        } else if (typeClass.isEnum()) {
          Class<RetentionPolicy> enumTypeClass = (Class<RetentionPolicy>) typeClass;
          return EnumParser.create(enumTypeClass, declaredNullable);
        } else if (type2typeHandler.containsKey(typeClass)) {
        }
        RefToType<?> ref = getTypeRef(typeClass);
        if (ref != null) {
          return createJsonParser(ref, declaredNullable, isSubtyping);
        }
        throw new JsonProtocolModelParseException("Method return type " + type +
            " (simple class) not supported");
      } else if (type instanceof ParameterizedType) {
        ParameterizedType parameterizedType = (ParameterizedType) type;
        if (parameterizedType.getRawType() == List.class) {
          Type argumentType = parameterizedType.getActualTypeArguments()[0];
          if (argumentType instanceof WildcardType) {
            WildcardType wildcard = (WildcardType) argumentType;
            if (wildcard.getLowerBounds().length == 0 && wildcard.getUpperBounds().length == 1) {
              argumentType = wildcard.getUpperBounds()[0];
            }
          }
          SlowParser<?> componentParser =
              getFieldTypeParser(argumentType, false, false, loadStrategy);
          return createArrayParser(componentParser, declaredNullable, loadStrategy);
        } else {
          throw new JsonProtocolModelParseException("Method return type " + type +
              " (generic) not supported");
        }
      } else {
        throw new JsonProtocolModelParseException("Method return type " + type + " not supported");
      }
    }

    private void nullableIsNotSupported(boolean declaredNullable)
        throws JsonProtocolModelParseException {
      if (declaredNullable) {
        throw new JsonProtocolModelParseException("The type cannot be declared nullable");
      }
    }

    private <T> JsonTypeParser<T> createJsonParser(RefToType<T> type, boolean isNullable,
        boolean isSubtyping) {
      return new JsonTypeParser<T>(type, isNullable, isSubtyping);
    }

    private <T> ArrayParser<T> createArrayParser(SlowParser<T> componentParser,
        boolean isNullable, FieldLoadStrategy loadStrategy) {
      if (loadStrategy == FieldLoadStrategy.LAZY) {
        return new ArrayParser<T>(componentParser, isNullable, ArrayParser.LAZY);
      } else {
        return new ArrayParser<T>(componentParser, isNullable, ArrayParser.EAGER);
      }
    }

    private <T> RefToType<T> getTypeRef(final Class<T> typeClass) {
      if (type2typeHandler.containsKey(typeClass)) {
        RefImpl<T> result = new RefImpl<T>(typeClass);
        refs.add(result);
        return result;
      }
      for (DynamicParserImpl baseParser : basePackages) {
        TypeHandler<?> typeHandler = baseParser.type2TypeHandler.get(typeClass);
        if (typeHandler != null) {
          final TypeHandler<T> typeHandlerT = (TypeHandler<T>) typeHandler;
          return new RefToType<T>() {
            @Override
            TypeHandler<T> get() {
              return typeHandlerT;
            }
            @Override
            Class<?> getTypeClass() {
              return typeClass;
            }
          };
        }
      }
      return null;
    }

    private RefToType<?> getSuperclassRef(Class<?> typeClass)
        throws JsonProtocolModelParseException {
      RefToType<?> result = null;
      for (Type interfc : typeClass.getGenericInterfaces()) {
        if (interfc instanceof ParameterizedType == false) {
          continue;
        }
        ParameterizedType parameterizedType = (ParameterizedType) interfc;
        if (parameterizedType.getRawType() != JsonSubtype.class) {
          continue;
        }
        Type param = parameterizedType.getActualTypeArguments()[0];
        if (param instanceof Class == false) {
          throw new JsonProtocolModelParseException("Unexpected type of superclass " + param);
        }
        Class<?> paramClass = (Class<?>) param;
        if (result != null) {
          throw new JsonProtocolModelParseException("Already has superclass " +
              result.getTypeClass().getName());
        }
        result = getTypeRef(paramClass);
        if (result == null) {
          throw new JsonProtocolModelParseException("Unknown base class " + paramClass.getName());
        }
      }
      return result;
    }

    class FieldProcessor<T> {
      private final Class<T> typeClass;

      private final JsonType jsonTypeAnn;
      private final List<FieldLoader> fieldLoaders = new ArrayList<FieldLoader>(2);
      private final List<LazyHandler> onDemandHanlers = new ArrayList<LazyHandler>();
      private final Map<Method, MethodHandler> methodHandlerMap =
          new HashMap<Method, MethodHandler>();
      private final FieldMap fieldMap = new FieldMap();
      private final List<FieldCondition> fieldConditions = new ArrayList<FieldCondition>(2);
      private ManualAlgebraicCasesDataImpl manualAlgCasesData = null;
      private AutoAlgebraicCasesDataImpl autoAlgCasesData = null;
      private int fieldArraySize = 0;
      private List<VolatileFieldBinding> volatileFields = new ArrayList<VolatileFieldBinding>(2);

      FieldProcessor(Class<T> typeClass) throws JsonProtocolModelParseException {
        this.typeClass = typeClass;
        jsonTypeAnn = typeClass.getAnnotation(JsonType.class);
        if (jsonTypeAnn == null) {
          throw new JsonProtocolModelParseException("Not a json model type: " + typeClass);
        }
      }

      void go() throws JsonProtocolModelParseException {
        for (Method m : typeClass.getDeclaredMethods()) {
          try {
            processMethod(m);
          } catch (JsonProtocolModelParseException e) {
            throw new JsonProtocolModelParseException("Problem with method " + m, e);
          }
        }
      }

      private void processMethod(Method m) throws JsonProtocolModelParseException {
        if (m.getParameterTypes().length != 0) {
          throw new JsonProtocolModelParseException("No parameters expected in " + m);
        }
        JsonOverrideField overrideFieldAnn = m.getAnnotation(JsonOverrideField.class);
        FieldConditionLogic fieldConditionLogic = FieldConditionLogic.readLogic(m);
        String fieldName = checkAndGetJsonFieldName(m);
        MethodHandler methodHandler;

        JsonSubtypeCasting jsonSubtypeCaseAnn = m.getAnnotation(JsonSubtypeCasting.class);
        if (jsonSubtypeCaseAnn != null) {
          if (fieldConditionLogic != null) {
            throw new JsonProtocolModelParseException(
                "Subtype condition annotation only works with field getter methods");
          }
          if (overrideFieldAnn != null) {
            throw new JsonProtocolModelParseException(
                "Override annotation only works with field getter methods");
          }

          if (jsonTypeAnn.subtypesChosenManually()) {
            if (manualAlgCasesData == null) {
              manualAlgCasesData = new ManualAlgebraicCasesDataImpl();
            }
            methodHandler = processManualSubtypeMethod(m, jsonSubtypeCaseAnn);
          } else {
            if (autoAlgCasesData == null) {
              autoAlgCasesData = new AutoAlgebraicCasesDataImpl();
            }
            if (jsonSubtypeCaseAnn.reinterpret()) {
              throw new JsonProtocolModelParseException(
                  "Option 'reinterpret' is only available with 'subtypes chosen manually'");
            }
            methodHandler = processAutomaticSubtypeMethod(m);
          }

        } else {
          methodHandler = processFieldGetterMethod(m, fieldConditionLogic, overrideFieldAnn,
              fieldName);
        }
        methodHandlerMap.put(m, methodHandler);
      }

      private MethodHandler processFieldGetterMethod(Method m,
          FieldConditionLogic fieldConditionLogic, JsonOverrideField overrideFieldAnn,
          String fieldName) throws JsonProtocolModelParseException {
        MethodHandler methodHandler;

        FieldLoadStrategy loadStrategy;
        if (m.getAnnotation(JsonField.class) == null) {
          loadStrategy = FieldLoadStrategy.AUTO;
        } else {
          loadStrategy = m.getAnnotation(JsonField.class).loadStrategy();
        }

        JsonNullable nullableAnn = m.getAnnotation(JsonNullable.class);
        SlowParser<?> fieldTypeParser = getFieldTypeParser(m.getGenericReturnType(),
            nullableAnn != null, false, loadStrategy);
        if (fieldConditionLogic != null) {
          fieldConditions.add(new FieldCondition(fieldName, fieldTypeParser.asQuickParser(),
              fieldConditionLogic));
        }
        if (overrideFieldAnn == null) {
          fieldMap.localNames.add(fieldName);
        } else {
          fieldMap.overridenNames.add(fieldName);
        }

        boolean isOptional = isOptionalField(m);

        if (fieldTypeParser.asQuickParser() != null) {
          QuickParser<?> quickParser = fieldTypeParser.asQuickParser();
          if (loadStrategy == FieldLoadStrategy.EAGER) {
            methodHandler = createEagerLoadGetterHandler(fieldName, fieldTypeParser, isOptional);
          } else {
            methodHandler = createLazyQuickGetterHandler(quickParser, isOptional, fieldName);
          }
        } else {
          if (loadStrategy == FieldLoadStrategy.LAZY) {
            methodHandler = createLazyCachedGetterHandler(fieldName, fieldTypeParser, isOptional);
          } else {
            methodHandler = createEagerLoadGetterHandler(fieldName, fieldTypeParser, isOptional);
          }
        }
        return methodHandler;
      }

      private MethodHandler createLazyQuickGetterHandler(QuickParser<?> quickParser,
          boolean isOptional, String fieldName) {
        LazyParseFieldMethodHandler onDemandHandler = new LazyParseFieldMethodHandler(quickParser,
            isOptional, fieldName, typeClass);
        onDemandHanlers.add(onDemandHandler);
        return onDemandHandler;
      }

      private MethodHandler createEagerLoadGetterHandler(String fieldName,
          SlowParser<?> fieldTypeParser, boolean isOptional) {
        int fieldCode = allocateFieldInArray();
        FieldLoader fieldLoader = new FieldLoader(fieldCode, fieldName, fieldTypeParser,
            isOptional);
        fieldLoaders.add(fieldLoader);
        return new PreparsedFieldMethodHandler(fieldCode,
            fieldTypeParser.getValueFinisher(), fieldName);
      }

      private MethodHandler createLazyCachedGetterHandler(String fieldName,
          SlowParser<?> fieldTypeParser, boolean isOptional) {
        VolatileFieldBinding fieldBinding = allocateVolatileField(fieldTypeParser, false);
        LazyCachedFieldMethodHandler lazyCachedHandler =
            new LazyCachedFieldMethodHandler(fieldBinding, fieldTypeParser, isOptional,
                fieldName, typeClass);
        onDemandHanlers.add(lazyCachedHandler);
        return lazyCachedHandler;
      }

      private MethodHandler processAutomaticSubtypeMethod(Method m)
          throws JsonProtocolModelParseException {
        MethodHandler methodHandler;
        if (m.getReturnType() == Void.TYPE) {
          if (autoAlgCasesData.hasDefaultCase) {
            throw new JsonProtocolModelParseException("Duplicate default case method: " + m);
          }
          autoAlgCasesData.hasDefaultCase = true;
          methodHandler = RETURN_NULL_METHOD_HANDLER;
        } else {
          Class<?> methodType = m.getReturnType();
          RefToType<?> ref = getTypeRef(methodType);
          if (ref == null) {
            throw new JsonProtocolModelParseException("Unknown return type in " + m);
          }
          if (autoAlgCasesData.variantCodeFieldPos == -1) {
            autoAlgCasesData.variantCodeFieldPos = allocateFieldInArray();
            autoAlgCasesData.variantValueFieldPos = allocateFieldInArray();
          }
          final int algCode = autoAlgCasesData.subtypes.size();
          autoAlgCasesData.subtypes.add(ref);
          final AutoSubtypeMethodHandler algMethodHandler = new AutoSubtypeMethodHandler(
              autoAlgCasesData.variantCodeFieldPos, autoAlgCasesData.variantValueFieldPos,
              algCode);
          methodHandler = algMethodHandler;

          SubtypeCaster subtypeCaster = new SubtypeCaster(typeClass, ref) {
            @Override
            ObjectData getSubtypeObjectData(ObjectData objectData) {
              return algMethodHandler.getFieldObjectData(objectData);
            }
          };

          subtypeCasters.add(subtypeCaster);
        }
        return methodHandler;
      }


      private MethodHandler processManualSubtypeMethod(final Method m,
          JsonSubtypeCasting jsonSubtypeCaseAnn) throws JsonProtocolModelParseException {

        SlowParser<?> fieldTypeParser = getFieldTypeParser(m.getGenericReturnType(), false,
            !jsonSubtypeCaseAnn.reinterpret(), FieldLoadStrategy.AUTO);

        VolatileFieldBinding fieldInfo = allocateVolatileField(fieldTypeParser, true);

        if (!Arrays.asList(m.getExceptionTypes()).contains(JsonProtocolParseException.class)) {
          throw new JsonProtocolModelParseException(
              "Method should declare JsonProtocolParseException exception: " + m);
        }

        final ManualSubtypeMethodHandler handler = new ManualSubtypeMethodHandler(fieldInfo,
            fieldTypeParser);
        JsonTypeParser<?> parserAsJsonTypeParser = fieldTypeParser.asJsonTypeParser();
        if (parserAsJsonTypeParser != null && parserAsJsonTypeParser.isSubtyping()) {
          SubtypeCaster subtypeCaster = new SubtypeCaster(typeClass,
              parserAsJsonTypeParser.getType()) {
            @Override
            ObjectData getSubtypeObjectData(ObjectData baseObjectData)
                throws JsonProtocolParseException {
              ObjectData objectData = baseObjectData;
              return handler.getSubtypeData(objectData);
            }
          };
          manualAlgCasesData.subtypes.add(parserAsJsonTypeParser.getType());
          subtypeCasters.add(subtypeCaster);
        }

        return handler;
      }

      int getFieldArraySize() {
        return fieldArraySize;
      }

      List<VolatileFieldBinding> getVolatileFields() {
        return volatileFields;
      }

      TypeHandler.AlgebraicCasesData getAlgCasesData() {
        if (jsonTypeAnn.subtypesChosenManually()) {
          return manualAlgCasesData;
        } else {
          return autoAlgCasesData;
        }
      }

      List<FieldLoader> getFieldLoaders() {
        return fieldLoaders;
      }

      List<LazyHandler> getOnDemandHanlers() {
        return onDemandHanlers;
      }

      Map<Method, MethodHandler> getMethodHandlerMap() {
        return methodHandlerMap;
      }

      List<FieldCondition> getFieldConditions() {
        return fieldConditions;
      }

      private int allocateFieldInArray() {
        return fieldArraySize++;
      }

      private VolatileFieldBinding allocateVolatileField(final SlowParser<?> fieldTypeParser,
          boolean internalType) {
        int position = volatileFields.size();
        FieldTypeInfo fieldTypeInfo;
        if (internalType) {
          fieldTypeInfo = new FieldTypeInfo() {
            // A field should store a value of internal type (we need its internal interface).
          };
        } else {
          fieldTypeInfo = new FieldTypeInfo() {
            // A field should store a value of user-visible type.
          };
        }
        VolatileFieldBinding binding = new VolatileFieldBinding(position, fieldTypeInfo);
        volatileFields.add(binding);
        return binding;
      }

      private boolean isOptionalField(Method m) {
        JsonOptionalField jsonOptionalFieldAnn = m.getAnnotation(JsonOptionalField.class);
        return jsonOptionalFieldAnn != null;
      }

      private String checkAndGetJsonFieldName(Method m) throws JsonProtocolModelParseException {
        if (m.getParameterTypes().length != 0) {
          throw new JsonProtocolModelParseException("Must have 0 parameters");
        }
        JsonField fieldAnn = m.getAnnotation(JsonField.class);
        if (fieldAnn != null) {
          String jsonLiteralName = fieldAnn.jsonLiteralName();
          if (!jsonLiteralName.isEmpty()) {
            return jsonLiteralName;
          }
        }
        return m.getName();
      }
    }
  }

  private static class EagerFieldParserImpl extends TypeHandler.EagerFieldParser {
    private final List<LazyHandler> onDemandHandlers;

    private EagerFieldParserImpl(List<LazyHandler> onDemandHandlers) {
      this.onDemandHandlers = onDemandHandlers;
    }

    @Override
    void parseAllFields(ObjectData objectData) throws JsonProtocolParseException {
      for (LazyHandler handler : onDemandHandlers) {
        handler.parseEager(objectData);
      }
    }
    @Override
    void addAllFieldNames(Set<? super String> output) {
      for (LazyHandler handler : onDemandHandlers) {
        output.add(handler.getFieldName());
      }
    }
  }

  private interface LazyHandler {
    void parseEager(ObjectData objectData) throws JsonProtocolParseException;
    String getFieldName();
  }

  private static class LazyParseFieldMethodHandler extends MethodHandler implements LazyHandler {
    private final QuickParser<?> quickParser;
    private final boolean isOptional;
    private final String fieldName;
    private final Class<?> typeClass;

    LazyParseFieldMethodHandler(QuickParser<?> quickParser, boolean isOptional, String fieldName,
        Class<?> typeClass) {
      this.quickParser = quickParser;
      this.isOptional = isOptional;
      this.fieldName = fieldName;
      this.typeClass = typeClass;
    }

    @Override
    Object handle(ObjectData objectData, Object[] args) {
      try {
        return parse(objectData);
      } catch (JsonProtocolParseException e) {
        throw new ParseRuntimeException(
            "On demand parsing failed for " + objectData.getUnderlyingObject(), e);
      }
    }

    @Override
    public void parseEager(ObjectData objectData) throws JsonProtocolParseException {
      parse(objectData);
    }

    public Object parse(ObjectData objectData) throws JsonProtocolParseException {
      Map<?,?> properties = (JSONObject)objectData.getUnderlyingObject();
      Object value = properties.get(fieldName);
      boolean hasValue;
      if (value == null) {
        hasValue = properties.containsKey(fieldName);
      } else {
        hasValue = true;
      }
      return parse(hasValue, value, objectData);
    }

    public Object parse(boolean hasValue, Object value, ObjectData objectData)
        throws JsonProtocolParseException {
      if (hasValue) {
        try {
          return quickParser.parseValueQuick(value);
        } catch (JsonProtocolParseException e) {
          throw new JsonProtocolParseException("Failed to parse field " + fieldName + " in type " +
              typeClass.getName(), e);
        }
      } else {
        if (!isOptional) {
          throw new JsonProtocolParseException("Field is not optional: " + fieldName +
              " (in type " + typeClass.getName() + ")");
        }
        return null;
      }
    }

    @Override
    public String getFieldName() {
      return fieldName;
    }

    @Override
    boolean requiresJsonObject() {
      return true;
    }
  }

  /**
   * Basic implementation of the method that parses value on demand and store it for
   * a future use.
   */
  private static abstract class LazyCachedMethodHandlerBase extends MethodHandler {
    private final VolatileFieldBinding fieldBinding;

    LazyCachedMethodHandlerBase(VolatileFieldBinding fieldBinding) {
      this.fieldBinding = fieldBinding;
    }

    @Override
    Object handle(ObjectData objectData, Object[] args) {
      try {
        return handle(objectData);
      } catch (JsonProtocolParseException e) {
        throw new ParseRuntimeException(
            "On demand parsing failed for " + objectData.getUnderlyingObject(), e);
      }
    }

    Object handle(ObjectData objectData) throws JsonProtocolParseException {
      Object raw = handleRaw(objectData);
      return finishRawValue(raw);
    }

    protected abstract Object finishRawValue(Object raw);

    Object handleRaw(ObjectData objectData) throws JsonProtocolParseException {
      AtomicReferenceArray<Object> atomicReferenceArray = objectData.getAtomicReferenceArray();

      Object cachedValue = fieldBinding.get(atomicReferenceArray);
      if (cachedValue != null) {
        return cachedValue;
      }

      Object parsedValue = parse(objectData);

      if (parsedValue != null) {
        parsedValue = fieldBinding.setAndGet(atomicReferenceArray, parsedValue);
      }
      return parsedValue;
    }

    protected abstract Object parse(ObjectData objectData) throws JsonProtocolParseException;

    protected VolatileFieldBinding getFieldBinding() {
      return fieldBinding;
    }
  }

  private static class LazyCachedFieldMethodHandler extends LazyCachedMethodHandlerBase
      implements LazyHandler {
    private final SlowParser<?> slowParser;
    private final boolean isOptional;
    private final String fieldName;
    private final Class<?> typeClass;

    LazyCachedFieldMethodHandler(VolatileFieldBinding fieldBinding, SlowParser<?> slowParser,
        boolean isOptional, String fieldName, Class<?> typeClass) {
      super(fieldBinding);
      this.slowParser = slowParser;
      this.isOptional = isOptional;
      this.fieldName = fieldName;
      this.typeClass = typeClass;
    }

    @Override
    public void parseEager(ObjectData objectData) throws JsonProtocolParseException {
      parse(objectData);
    }

    @Override
    protected Object parse(ObjectData objectData) throws JsonProtocolParseException {
      Map<?,?> properties = (JSONObject)objectData.getUnderlyingObject();
      Object value = properties.get(fieldName);
      boolean hasValue;
      if (value == null) {
        hasValue = properties.containsKey(fieldName);
      } else {
        hasValue = true;
      }
      Object parsedValue = parse(hasValue, value, objectData);
      // Cache already finished value, because we don't use unfinished value anywhere.
      FieldLoadedFinisher valueFinisher = slowParser.getValueFinisher();
      if (valueFinisher != null) {
        parsedValue = valueFinisher.getValueForUser(parsedValue);
      }
      return parsedValue;
    }

    @Override
    protected Object finishRawValue(Object raw) {
      return raw;
    }

    private Object parse(boolean hasValue, Object value, ObjectData objectData)
        throws JsonProtocolParseException {
      if (hasValue) {
        try {
          return slowParser.parseValue(value, objectData);
        } catch (JsonProtocolParseException e) {
          throw new JsonProtocolParseException("Failed to parse field " + fieldName + " in type " +
              typeClass.getName(), e);
        }
      } else {
        if (!isOptional) {
          throw new JsonProtocolParseException("Field is not optional: " + fieldName +
              " (in type " + typeClass.getName() + ")");
        }
        return null;
      }
    }

    @Override
    public String getFieldName() {
      return fieldName;
    }

    @Override
    boolean requiresJsonObject() {
      return true;
    }
  }

  private static class PreparsedFieldMethodHandler extends MethodHandler {
    private final int pos;
    private final FieldLoadedFinisher valueFinisher;
    private final String fieldName;

    PreparsedFieldMethodHandler(int pos, FieldLoadedFinisher valueFinisher, String fieldName) {
      this.pos = pos;
      this.valueFinisher = valueFinisher;
      this.fieldName = fieldName;
    }

    @Override
    Object handle(ObjectData objectData, Object[] args) throws Throwable {
      Object val = objectData.getFieldArray()[pos];
      if (valueFinisher != null) {
        val = valueFinisher.getValueForUser(val);
      }
      return val;
    }

    @Override
    boolean requiresJsonObject() {
      return false;
    }
  }

  static SlowParser<Void> VOID_PARSER = new QuickParser<Void>() {
    @Override
    public Void parseValueQuick(Object value) {
      return null;
    }
  };

  static class SimpleCastParser<T> extends QuickParser<T> {
    private final boolean nullable;
    private final Class<T> fieldType;

    SimpleCastParser(Class<T> fieldType, boolean nullable) {
      this.fieldType = fieldType;
      this.nullable = nullable;
    }

    @Override
    public T parseValueQuick(Object value) throws JsonProtocolParseException {
      if (value == null) {
        if (nullable) {
          return null;
        } else {
          throw new JsonProtocolParseException("Field must have type " + fieldType.getName());
        }
      }
      try {
        return fieldType.cast(value);
      } catch (ClassCastException e) {
        throw new JsonProtocolParseException("Field must have type " + fieldType.getName(), e);
      }
    }

    @Override
    public FieldLoadedFinisher getValueFinisher() {
      return null;
    }
  }

  static class SimpleParserPair<T> {
    static <T> SimpleParserPair<T> create(Class<T> fieldType) {
      return new SimpleParserPair<T>(fieldType);
    }

    private final SimpleCastParser<T> nullable;
    private final SimpleCastParser<T> notNullable;

    private SimpleParserPair(Class<T> fieldType) {
      nullable = new SimpleCastParser<T>(fieldType, true);
      notNullable = new SimpleCastParser<T>(fieldType, false);
    }

    SimpleCastParser<T> getNullable() {
      return nullable;
    }

    SimpleCastParser<T> getNotNullable() {
      return notNullable;
    }

    SlowParser<?> get(boolean declaredNullable) {
      return declaredNullable ? nullable : notNullable;
    }
  }

  private static SimpleParserPair<Long> LONG_PARSER = SimpleParserPair.create(Long.class);
  private static SimpleParserPair<Boolean> BOOLEAN_PARSER = SimpleParserPair.create(Boolean.class);
  private static SimpleParserPair<Float> FLOAT_PARSER = SimpleParserPair.create(Float.class);
  private static SimpleParserPair<String> STRING_PARSER = SimpleParserPair.create(String.class);
  private static SimpleParserPair<Object> OBJECT_PARSER = SimpleParserPair.create(Object.class);
  private static SimpleParserPair<JSONObject> JSON_PARSER =
      SimpleParserPair.create(JSONObject.class);

  static class ArrayParser<T> extends SlowParser<List<? extends T>> {

    static abstract class ListFactory {
      abstract <T> List<T> create(JSONArray array, SlowParser<T> componentParser)
          throws JsonProtocolParseException;
    }

    static final ListFactory EAGER = new ListFactory() {
      @Override
      <T> List<T> create(JSONArray array, SlowParser<T> componentParser)
          throws JsonProtocolParseException {
        int size = array.size();
        List list = new ArrayList<Object>(size);
        FieldLoadedFinisher valueFinisher = componentParser.getValueFinisher();
        for (int i = 0; i < size; i++) {
          // We do not support super object for array component.
          Object val = componentParser.parseValue(array.get(i), null);
          if (valueFinisher != null) {
            val = valueFinisher.getValueForUser(val);
          }
          list.add(val);
        }
        return Collections.unmodifiableList(list);
      }
    };

    static final ListFactory LAZY = new ListFactory() {
      @Override
      <T> List<T> create(final JSONArray array, final SlowParser<T> componentParser) {
        final int size = array.size();
        List<T> list = new AbstractList<T>() {
          private final AtomicReferenceArray<T> values = new AtomicReferenceArray<T>(size);

          @Override
          public synchronized T get(int index) {
            T parsedValue = values.get(index);
            if (parsedValue == null) {
              Object rawObject = array.get(index);
              if (rawObject != null) {
                Object parsedObject;
                try {
                  parsedObject = componentParser.parseValue(array.get(index), null);
                } catch (JsonProtocolParseException e) {
                  throw new ParseRuntimeException(e);
                }
                FieldLoadedFinisher valueFinisher = componentParser.getValueFinisher();
                if (valueFinisher != null) {
                  parsedObject = valueFinisher.getValueForUser(parsedObject);
                }
                parsedValue = (T) parsedObject;
                values.compareAndSet(index, null, parsedValue);
                parsedValue = values.get(index);
              }
            }
            return parsedValue;
          }

          @Override
          public int size() {
            return size;
          }
        };
        return list;
      }
    };

    private final SlowParser<T> componentParser;
    private final boolean isNullable;
    private final ListFactory listFactory;

    ArrayParser(SlowParser<T> componentParser, boolean isNullable, ListFactory listFactory) {
      this.componentParser = componentParser;
      this.isNullable = isNullable;
      this.listFactory = listFactory;
    }

    @Override
    public List<? extends T> parseValue(Object value, ObjectData thisData)
        throws JsonProtocolParseException {
      if (isNullable && value == null) {
        return null;
      }
      if (value instanceof JSONArray == false) {
        throw new JsonProtocolParseException("Array value expected");
      }
      JSONArray arrayValue = (JSONArray) value;
      return listFactory.create(arrayValue, componentParser);
    }

    @Override
    public FieldLoadedFinisher getValueFinisher() {
      return null;
    }

    @Override
    public JsonTypeParser<?> asJsonTypeParser() {
      return null;
    }
  }

  static MethodHandler RETURN_NULL_METHOD_HANDLER = new MethodHandler() {
    @Override
    Object handle(ObjectData objectData, Object[] args) throws Throwable {
      return null;
    }

    @Override
    boolean requiresJsonObject() {
      return false;
    }
  };

  static class AutoSubtypeMethodHandler extends MethodHandler {
    private final int variantCodeField;
    private final int variantValueField;
    private final int code;

    AutoSubtypeMethodHandler(int variantCodeField, int variantValueField, int code) {
      this.variantCodeField = variantCodeField;
      this.variantValueField = variantValueField;
      this.code = code;
    }

    ObjectData getFieldObjectData(ObjectData objectData) {
      Object[] array = objectData.getFieldArray();
      Integer actualCode = (Integer) array[variantCodeField];
      if (this.code == actualCode) {
        ObjectData data = (ObjectData) array[variantValueField];
        return data;
      } else {
        return null;
      }
    }

    @Override
    Object handle(ObjectData objectData, Object[] args) {
      ObjectData resData = getFieldObjectData(objectData);
      if (resData == null) {
        return null;
      } else {
        return resData.getProxy();
      }
    }

    @Override
    boolean requiresJsonObject() {
      return false;
    }
  }

  static class ManualSubtypeMethodHandler extends LazyCachedMethodHandlerBase {
    private final SlowParser<?> parser;

    ManualSubtypeMethodHandler(VolatileFieldBinding fieldInf, SlowParser<?> parser) {
      super(fieldInf);
      this.parser = parser;
    }

    @Override
    protected Object parse(ObjectData objectData) throws JsonProtocolParseException {
      return parser.parseValue(objectData.getUnderlyingObject(), objectData);
    }

    @Override
    protected Object finishRawValue(Object raw) {
      FieldLoadedFinisher valueFinisher = parser.getValueFinisher();
      Object res = raw;
      if (valueFinisher != null) {
         res = valueFinisher.getValueForUser(res);
      }
      return res;
    }

    ObjectData getSubtypeData(ObjectData objectData) throws JsonProtocolParseException {
      return (ObjectData) handleRaw(objectData);
    }

    @Override
    boolean requiresJsonObject() {
      return false;
    }
  }

  static class AutoAlgebraicCasesDataImpl extends TypeHandler.AlgebraicCasesData {
    private int variantCodeFieldPos = -1;
    private int variantValueFieldPos = -1;
    private boolean hasDefaultCase = false;
    private final List<RefToType<?>> subtypes = new ArrayList<RefToType<?>>();


    @Override
    List<RefToType<?>> getSubtypes() {
      return subtypes;
    }

    @Override
    boolean underlyingIsJson() {
      return true;
    }


    @Override
    void parseObjectSubtype(ObjectData objectData, Map<?, ?> jsonProperties,
        Object input) throws JsonProtocolParseException {
      if (jsonProperties == null) {
        throw new JsonProtocolParseException(
            "JSON object input expected for non-manual subtyping");
      }
      int code = -1;
      for (int i = 0; i < this.getSubtypes().size(); i++) {
        TypeHandler<?> nextSubtype = this.getSubtypes().get(i).get();
        boolean ok = nextSubtype.getSubtypeSupport().checkConditions(jsonProperties);
        if (ok) {
          if (code == -1) {
            code = i;
          } else {
            throw new JsonProtocolParseException("More than one case match");
          }
        }
      }
      if (code == -1) {
        if (!this.hasDefaultCase) {
          throw new JsonProtocolParseException("Not a singe case matches");
        }
      } else {
        ObjectData fieldData =
            this.getSubtypes().get(code).get().parse(input, objectData);
        objectData.getFieldArray()[this.variantValueFieldPos] = fieldData;
      }
      objectData.getFieldArray()[this.variantCodeFieldPos] =
          Integer.valueOf(code);
    }
  }


  static class ManualAlgebraicCasesDataImpl extends TypeHandler.AlgebraicCasesData {
    private final List<RefToType<?>> subtypes = new ArrayList<RefToType<?>>();

    @Override
    List<RefToType<?>> getSubtypes() {
      return subtypes;
    }

    @Override
    boolean underlyingIsJson() {
      return false;
    }

    @Override
    void parseObjectSubtype(ObjectData objectData, Map<?, ?> jsonProperties, Object input) {
    }
  }

  static class VolatileFieldBinding {
    private final int position;
    private final FieldTypeInfo fieldTypeInfo;

    public VolatileFieldBinding(int position, FieldTypeInfo fieldTypeInfo) {
      this.position = position;
      this.fieldTypeInfo = fieldTypeInfo;
    }

    public Object setAndGet(AtomicReferenceArray<Object> atomicReferenceArray,
        Object value) {
      atomicReferenceArray.compareAndSet(position, null, value);
      return atomicReferenceArray.get(position);
    }

    public Object get(AtomicReferenceArray<Object> atomicReferenceArray) {
      return atomicReferenceArray.get(position);
    }

    FieldTypeInfo getTypeInfo() {
      return fieldTypeInfo;
    }
  }

  private static class RefImpl<T> extends RefToType<T> {
    private final Class<T> typeClass;
    private TypeHandler<T> type = null;

    RefImpl(Class<T> typeClass) {
      this.typeClass = typeClass;
    }

    @Override
    Class<?> getTypeClass() {
      return typeClass;
    }

    @Override
    TypeHandler<T> get() {
      return type;
    }

    void set(TypeHandler<?> type) {
      this.type = (TypeHandler<T>)type;
    }
  }

  // We should use it for static analysis later.
  private static class FieldMap {
    final List<String> localNames = new ArrayList<String>(5);
    final List<String> overridenNames = new ArrayList<String>(1);
  }
}
