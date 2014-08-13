package com.github.trickster88.xjcplugins;


import com.sun.codemodel.*;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.Plugin;
import com.sun.tools.xjc.model.CAttributePropertyInfo;
import com.sun.tools.xjc.model.CElementPropertyInfo;
import com.sun.tools.xjc.model.CPropertyInfo;
import com.sun.tools.xjc.outline.ClassOutline;
import com.sun.tools.xjc.outline.Outline;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import javax.annotation.Nullable;

public class NullablePlugin extends Plugin {
    private static final String OPTION_NAME = "Xnullable";

    @Override public String getOptionName() {
        return OPTION_NAME;
    }

    @Override public String getUsage() {
        return "  -" + OPTION_NAME + "\t  : xjc generate nullable annotations for optional properties";
    }

    @Override public boolean run(Outline model, Options opt, ErrorHandler errorHandler) throws SAXException {
        for(ClassOutline classOutline : model.getClasses()) {
            for(CPropertyInfo property : classOutline.target.getProperties()) {
                if(property instanceof CElementPropertyInfo && !((CElementPropertyInfo) property).isRequired()) {
                    processXmlProperty(classOutline, property);
                } else if(property instanceof CAttributePropertyInfo && !((CAttributePropertyInfo) property).isRequired()) {
                    processXmlProperty(classOutline, property);
                }
            }
        }

        return true;
    }

    private void processXmlProperty(ClassOutline classOutline, CPropertyInfo property) {
        JDefinedClass definedClass = classOutline.ref;

        if(definedClass.isInterface()) {
            annotateJavaProperty(property, classOutline.implClass);
        }
        annotateJavaProperty(property, definedClass);
    }

    private void annotateJavaProperty(CPropertyInfo property, JDefinedClass definedClass) {
        JFieldVar field = definedClass.fields().get(property.getName(false));
        // Field is null for interfaces
        if(field != null && !hasAnnotation(field, Nullable.class)) {
            field.annotate(Nullable.class);
        }

        @Nullable JMethod getter = findGetter(property.getName(true), definedClass);
        if(getter != null && !hasAnnotation(getter, Nullable.class)) {
            getter.annotate(Nullable.class);
        }

        @Nullable JMethod setter = findSetter(property.getName(true), definedClass);
        if(setter != null && !hasAnnotation(setter.listParams()[0], Nullable.class)) {
            setter.listParams()[0].annotate(Nullable.class);
        }
    }

    private @Nullable JMethod findGetter(String name, JDefinedClass definedClass) {
        for(JMethod method : definedClass.methods()) {
            String methodName = method.name();
            if(methodName.equals("is" + name) || methodName.equals("get" + name)) {
                return method;
            }
        }

        return null;
    }

    private @Nullable JMethod findSetter(String name, JDefinedClass definedClass) {
        for(JMethod method : definedClass.methods()) {
            String methodName = method.name();
            if(methodName.equals("set" + name)) {
                return method;
            }
        }

        return null;
    }

    private boolean hasAnnotation(JAnnotatable annotatable, Class referenceAnnotation) {
        for(JAnnotationUse annotation : annotatable.annotations()) {
            JClass actualAnnotation = annotation.getAnnotationClass();
            if(actualAnnotation.owner().ref(referenceAnnotation).equals(actualAnnotation)) {
                return true;
            }
        }
        return false;
    }
}
