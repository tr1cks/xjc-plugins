package com.github.tr1cks.xjcplugins;


import com.sun.codemodel.*;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.Plugin;
import com.sun.tools.xjc.model.CAttributePropertyInfo;
import com.sun.tools.xjc.model.CElementPropertyInfo;
import com.sun.tools.xjc.model.CPropertyInfo;
import com.sun.tools.xjc.outline.ClassOutline;
import com.sun.tools.xjc.outline.Outline;
import com.sun.xml.xsom.*;
import com.sun.xml.xsom.impl.AttributeUseImpl;
import com.sun.xml.xsom.impl.ElementDecl;
import com.sun.xml.xsom.impl.ParticleImpl;
import com.sun.xml.xsom.impl.parser.DelayedRef;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.math.BigInteger;

import static com.sun.xml.xsom.XSFacet.*;
import static java.math.BigInteger.ONE;

public class NumberTypePlugin extends Plugin {
    private static final String OPTION_NAME = "XnumberType";

    @Override public String getOptionName() {
        return OPTION_NAME;
    }

    @Override public String getUsage() {
        return "  -" + OPTION_NAME + "\t  : xjc change number types to more appropriate";
    }

    @Override public boolean run(Outline model, Options opt, ErrorHandler errorHandler) throws SAXException {
        for(ClassOutline classOutline : model.getClasses()) {
            for(CPropertyInfo property : classOutline.target.getProperties()) {
                String propertyName = property.getName(false);
                JFieldVar field = classOutline.implClass.fields().get(propertyName);

                XSSimpleType xsSimpleType;
                boolean isRequired;
                if(property instanceof CAttributePropertyInfo) {
                    AttributeUseImpl particle = (AttributeUseImpl) property.getSchemaComponent();
                    isRequired = particle.isRequired();
                    xsSimpleType = particle.getDecl().getType();
                } else if(property instanceof CElementPropertyInfo) {
                    isRequired = ((CElementPropertyInfo) property).isRequired();

                    //TODO: refactor me
                    XSTerm term = ((ParticleImpl) property.getSchemaComponent()).getTerm();
                    ElementDecl elementDecl;
                    if(term instanceof ElementDecl) {
                        elementDecl = (ElementDecl) term;
                    } else if(term instanceof DelayedRef.Element) {
                        elementDecl = (ElementDecl) ((DelayedRef.Element) term).get();
                    } else {
                        continue;
                    }

                    XSType elementType = elementDecl.getType();
                    if(elementType instanceof XSSimpleType) {
                        xsSimpleType = (XSSimpleType) elementType;
                    } else if(elementType.getBaseType() instanceof XSSimpleType) {
                        xsSimpleType = (XSSimpleType) elementType.getBaseType();
                    } else {
                        continue;
                    }
                } else {
                    continue;
                }

                @Nullable JType newType = findBetterIntType(xsSimpleType, field.type(), isRequired);
                if(newType != null && !newType.equals(field.type())) {
                    JavaBeanProperty.findProperty(propertyName, classOutline.ref).changeType(newType);

                    if(classOutline.ref.isInterface()) {
                        JavaBeanProperty.findProperty(propertyName, classOutline.implClass).changeType(newType);
                    }
                }
            }
        }

        return true;
    }

    public @Nullable JType findBetterIntType(XSSimpleType simpleType, JType fieldType, boolean isRequired) {
        if(!isIntegerType(fieldType)) return null;

        @Nullable BigInteger totalDigits = fetchFacetIntValue(simpleType, FACET_TOTALDIGITS);
        @Nullable BigInteger minInclusive = getMinInclusive(simpleType);
        @Nullable BigInteger maxInclusive = getMaxInclusive(simpleType);

        JCodeModel codeModel = fieldType.owner();
        @Nullable JType bestBoxifiedTypeForField = determineBestType(totalDigits, minInclusive, maxInclusive, codeModel);

        if(bestBoxifiedTypeForField != null) {
            return isRequired ? bestBoxifiedTypeForField.unboxify() : bestBoxifiedTypeForField;
        } else {
            return null;
        }
    }

    private boolean isIntegerType(JType type) {
        JClass boxifiedFieldType = type.boxify();
        Class<?> fieldWrapperClass = getClass(boxifiedFieldType);

        if(!Number.class.isAssignableFrom(fieldWrapperClass)) {
            return false;
        } else {
            return !(Float.class.isAssignableFrom(fieldWrapperClass) || Double.class.isAssignableFrom(fieldWrapperClass) ||
                    BigDecimal.class.isAssignableFrom(fieldWrapperClass));
        }
    }

    private @Nullable JType determineBestType(BigInteger totalDigits, BigInteger minInclusive, BigInteger maxInclusive,
                                              JCodeModel codeModel)
    {
        if(isMatches(totalDigits, minInclusive, maxInclusive, (short) 2, Byte.MIN_VALUE, Byte.MAX_VALUE)) {
            return codeModel._ref(Byte.class);
        } else if(isMatches(totalDigits, minInclusive, maxInclusive, (short) 4, Short.MIN_VALUE, Short.MAX_VALUE)) {
            return codeModel._ref(Short.class);
        } else if(isMatches(totalDigits, minInclusive, maxInclusive, (short) 9, Integer.MIN_VALUE, Integer.MAX_VALUE)) {
            return codeModel._ref(Integer.class);
        } else if(isMatches(totalDigits, minInclusive, maxInclusive, (short) 18, Long.MIN_VALUE, Long.MAX_VALUE)) {
            return codeModel._ref(Long.class);
        } else {
            return null;
        }
    }

    private boolean isMatches(BigInteger totalDigits, BigInteger minInclusive, BigInteger maxInclusive,
                              short referenceTotalDigits, long referenceMinInclusive, long referenceMaxInclusive)
    {
        if(totalDigits != null && totalDigits.compareTo(BigInteger.valueOf(referenceTotalDigits)) <= 0) {
            return true;
        } else if(minInclusive != null && maxInclusive != null &&
                  minInclusive.compareTo(BigInteger.valueOf(referenceMinInclusive)) >= 0 &&
                  maxInclusive.compareTo(BigInteger.valueOf(referenceMaxInclusive)) <= 0) {
            return true;
        } else {
            return false;
        }
    }

    private @Nullable BigInteger getMinInclusive(XSSimpleType simpleType) {
        @Nullable BigInteger minInclusive = fetchFacetIntValue(simpleType, FACET_MININCLUSIVE);
        @Nullable BigInteger minExclusive = fetchFacetIntValue(simpleType, FACET_MINEXCLUSIVE);

        //TODO: bug? Maybe expected getMinInclusive?
        return getMaxInclusive(minInclusive != null ? minInclusive.negate() : null,
                               minExclusive != null ? minExclusive.negate().subtract(ONE) : null);
    }

    private @Nullable BigInteger getMaxInclusive(XSSimpleType simpleType) {
        @Nullable BigInteger maxInclusive = fetchFacetIntValue(simpleType, FACET_MAXINCLUSIVE);
        @Nullable BigInteger maxExclusive = fetchFacetIntValue(simpleType, FACET_MAXEXCLUSIVE);

        return getMaxInclusive(maxInclusive, maxExclusive != null ? maxExclusive.subtract(ONE) : null);
    }

    private BigInteger getMaxInclusive(@Nullable BigInteger maxInclusive, @Nullable BigInteger maxExclusiveMinusOne) {
        if(maxInclusive == null) {
            return maxExclusiveMinusOne;
        } else if(maxExclusiveMinusOne == null) {
            return maxInclusive;
        } else {
            return maxInclusive.compareTo(maxExclusiveMinusOne) <= 0 ? maxInclusive : maxExclusiveMinusOne;
        }
    }

    private @Nullable BigInteger fetchFacetIntValue(XSSimpleType simpleType, String facetName) {
        @Nullable XSFacet facet = simpleType.getFacet(facetName);

        return facet == null ? null : new BigInteger(facet.getValue().value);
    }

    private Class<?> getClass(JType jType) {
        try {
            return Class.forName(jType.fullName());
        } catch(ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private static class JavaBeanProperty {
        private final String classFullName;
        private final @Nullable JFieldVar field;
        private final @Nullable JMethod getter;
        private final @Nullable JMethod setter;

        private JavaBeanProperty(String classFullName, @Nullable JFieldVar field, @Nullable JMethod getter,
                                 @Nullable JMethod setter)
        {
            this.classFullName = classFullName;
            this.field = field;
            this.getter = getter;
            this.setter = setter;
        }

        public void changeType(JType newType) {
            if(field != null) {
                field.type(newType);
                System.out.println(classFullName + "::" + field.name() + " changed to " + newType.name());
            }
            if(getter != null) {
                getter.type(newType);
                System.out.println(classFullName + "::" + getter.name() + "() changed to " + newType.name());
            }
            if(setter != null) {
                setter.listParams()[0].type(newType);
                System.out.println(classFullName + "::" + setter.name() + "(...) changed to " + newType.name());
            }
        }

        public static @Nullable JavaBeanProperty findProperty(String fieldName, JDefinedClass definedClass) {
            @Nullable JFieldVar field = definedClass.fields().get(fieldName);
            String propertyName = fieldNameToUpperCamelCase(fieldName);
            @Nullable JMethod getter = findGetter(propertyName, definedClass);
            @Nullable JMethod setter = findSetter(propertyName, definedClass);

            if(field != null || getter != null || setter != null) {
                return new JavaBeanProperty(definedClass.fullName(), field, getter, setter);
            }

            return null;
        }

        private static @Nullable JMethod findGetter(String propertyName, JDefinedClass definedClass) {
            for(JMethod method : definedClass.methods()) {
                String methodName = method.name();
                if(methodName.equals("is" + propertyName) || methodName.equals("get" + fieldNameToUpperCamelCase(propertyName))) {
                    return method;
                }
            }

            return null;
        }

        private static @Nullable JMethod findSetter(String propertyName, JDefinedClass definedClass) {
            for(JMethod method : definedClass.methods()) {
                String methodName = method.name();
                if(methodName.equals("set" + propertyName)) {
                    return method;
                }
            }

            return null;
        }

        private static String fieldNameToUpperCamelCase(String fieldName) {
            return Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        }
    }
}
