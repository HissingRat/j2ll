package xyz.melodysky.runtime.metadata;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.List;
import xyz.melodysky.analysis.reflection.ReflectionFieldTarget;
import xyz.melodysky.analysis.reflection.ReflectionMethodTarget;
import xyz.melodysky.analysis.reflection.ReflectionPlan;
import xyz.melodysky.analysis.reflection.ReflectionUnsupportedSite;

public final class RuntimeMetadataDumpWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .setPrettyPrinting()
            .create();

    public String write(RuntimeMetadataIndex index) {
        return write(index, null);
    }

    public String write(RuntimeMetadataIndex index, ReflectionPlan reflectionPlan) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        JsonArray classes = new JsonArray();
        for (ClassMetadata metadata : index.classes()) {
            classes.add(classJson(metadata));
        }
        root.add("classes", classes);
        if (reflectionPlan != null) {
            root.add("reflectionReachability", reflectionJson(reflectionPlan));
        }
        return GSON.toJson(root) + "\n";
    }

    private JsonObject reflectionJson(ReflectionPlan plan) {
        JsonObject object = new JsonObject();
        JsonArray classes = new JsonArray();
        plan.resolvedClasses().forEach(target -> {
            JsonObject targetJson = new JsonObject();
            targetJson.addProperty("class", target.internalName());
            targetJson.addProperty("requiresClassInitialization", target.requiresClassInitialization());
            targetJson.addProperty("sourceSite", target.sourceSite());
            classes.add(targetJson);
        });
        object.add("classes", classes);

        JsonArray methods = new JsonArray();
        for (ReflectionMethodTarget target : plan.resolvedMethods()) {
            JsonObject targetJson = new JsonObject();
            targetJson.addProperty("class", target.owner());
            targetJson.addProperty("method", target.name());
            targetJson.addProperty("descriptor", target.descriptor());
            targetJson.addProperty("kind", target.kind().name());
            targetJson.addProperty("requiresClassInitialization", target.requiresClassInitialization());
            targetJson.addProperty("sourceSite", target.sourceSite());
            methods.add(targetJson);
        }
        object.add("methods", methods);

        JsonArray fields = new JsonArray();
        for (ReflectionFieldTarget target : plan.resolvedFields()) {
            JsonObject targetJson = new JsonObject();
            targetJson.addProperty("class", target.owner());
            targetJson.addProperty("field", target.name());
            targetJson.addProperty("descriptor", target.descriptor());
            targetJson.addProperty("sourceSite", target.sourceSite());
            fields.add(targetJson);
        }
        object.add("fields", fields);

        JsonArray unsupportedSites = new JsonArray();
        for (ReflectionUnsupportedSite site : plan.unsupportedSites()) {
            JsonObject siteJson = new JsonObject();
            siteJson.addProperty("class", site.owner());
            siteJson.addProperty("method", site.method());
            siteJson.addProperty("descriptor", site.descriptor());
            siteJson.addProperty("instructionIndex", site.instructionIndex());
            siteJson.addProperty("reasonCode", site.reasonCode());
            siteJson.addProperty("reason", site.reason());
            unsupportedSites.add(siteJson);
        }
        object.add("unsupportedSites", unsupportedSites);
        return object;
    }

    private JsonObject classJson(ClassMetadata metadata) {
        JsonObject object = new JsonObject();
        object.addProperty("internalName", metadata.internalName());
        object.addProperty("binaryName", metadata.binaryName());
        object.add("accessFlags", stringArray(metadata.accessFlags()));
        object.add("compilerFlags", stringArray(metadata.compilerFlags()));
        object.addProperty("majorVersion", metadata.majorVersion());
        object.addProperty("minorVersion", metadata.minorVersion());
        nullableString(object, "superName", metadata.superName());
        object.add("interfaces", stringArray(metadata.interfaces()));
        object.add("signature", signatureJson(metadata.signature()));
        object.add("annotations", annotationsJson(metadata.annotations()));
        object.add("fields", fieldsJson(metadata.fields()));
        object.add("methods", methodsJson(metadata.methods()));
        object.add("record", recordJson(metadata.recordMetadata()));
        object.add("nest", nestJson(metadata.nestMetadata()));
        object.add("innerClasses", innerClassesJson(metadata.innerClasses()));
        object.add("classInit", classInitJson(metadata.classInitMetadata()));
        return object;
    }

    private JsonObject fieldJson(FieldMetadata metadata) {
        JsonObject object = new JsonObject();
        object.addProperty("name", metadata.name());
        object.addProperty("descriptor", metadata.descriptor());
        object.add("accessFlags", stringArray(metadata.accessFlags()));
        object.add("compilerFlags", stringArray(metadata.compilerFlags()));
        object.add("signature", signatureJson(metadata.signature()));
        object.add("annotations", annotationsJson(metadata.annotations()));
        return object;
    }

    private JsonObject methodJson(MethodMetadata metadata) {
        JsonObject object = new JsonObject();
        object.addProperty("name", metadata.name());
        object.addProperty("descriptor", metadata.descriptor());
        object.add("accessFlags", stringArray(metadata.accessFlags()));
        object.add("compilerFlags", stringArray(metadata.compilerFlags()));
        object.add("signature", signatureJson(metadata.signature()));
        object.add("annotations", annotationsJson(metadata.annotations()));
        object.add("exceptions", stringArray(metadata.exceptions()));
        object.addProperty("hasCode", metadata.hasCode());
        return object;
    }

    private JsonObject annotationJson(AnnotationMetadata metadata) {
        JsonObject object = new JsonObject();
        object.addProperty("descriptor", metadata.descriptor());
        object.addProperty("runtimeVisible", metadata.runtimeVisible());
        JsonObject values = new JsonObject();
        metadata.values().forEach(values::addProperty);
        object.add("values", values);
        return object;
    }

    private JsonObject signatureJson(SignatureMetadata metadata) {
        JsonObject object = new JsonObject();
        nullableString(object, "signature", metadata.signature());
        object.addProperty("present", metadata.present());
        return object;
    }

    private JsonObject recordJson(RecordMetadata metadata) {
        JsonObject object = new JsonObject();
        object.addProperty("recordClass", metadata.recordClass());
        JsonArray components = new JsonArray();
        for (RecordComponentMetadata component : metadata.components()) {
            JsonObject componentJson = new JsonObject();
            componentJson.addProperty("name", component.name());
            componentJson.addProperty("descriptor", component.descriptor());
            componentJson.add("signature", signatureJson(component.signature()));
            componentJson.add("annotations", annotationsJson(component.annotations()));
            components.add(componentJson);
        }
        object.add("components", components);
        return object;
    }

    private JsonObject nestJson(NestMetadata metadata) {
        JsonObject object = new JsonObject();
        nullableString(object, "nestHost", metadata.nestHost());
        object.add("nestMembers", stringArray(metadata.nestMembers()));
        nullableString(object, "outerClass", metadata.outerClass());
        nullableString(object, "outerMethodName", metadata.outerMethodName());
        nullableString(object, "outerMethodDescriptor", metadata.outerMethodDescriptor());
        return object;
    }

    private JsonObject innerClassJson(InnerClassMetadata metadata) {
        JsonObject object = new JsonObject();
        object.addProperty("name", metadata.name());
        nullableString(object, "outerName", metadata.outerName());
        nullableString(object, "innerName", metadata.innerName());
        object.add("accessFlags", stringArray(metadata.accessFlags()));
        object.add("compilerFlags", stringArray(metadata.compilerFlags()));
        return object;
    }

    private JsonObject classInitJson(ClassInitMetadata metadata) {
        JsonObject object = new JsonObject();
        object.addProperty("hasClassInitializer", metadata.hasClassInitializer());
        object.addProperty("classObjectHandle", metadata.classObjectHandle());
        object.addProperty("initStateHandle", metadata.initStateHandle());
        return object;
    }

    private JsonArray fieldsJson(List<FieldMetadata> values) {
        JsonArray array = new JsonArray();
        for (FieldMetadata value : values) {
            array.add(fieldJson(value));
        }
        return array;
    }

    private JsonArray methodsJson(List<MethodMetadata> values) {
        JsonArray array = new JsonArray();
        for (MethodMetadata value : values) {
            array.add(methodJson(value));
        }
        return array;
    }

    private JsonArray annotationsJson(List<AnnotationMetadata> values) {
        JsonArray array = new JsonArray();
        for (AnnotationMetadata value : values) {
            array.add(annotationJson(value));
        }
        return array;
    }

    private JsonArray innerClassesJson(List<InnerClassMetadata> values) {
        JsonArray array = new JsonArray();
        for (InnerClassMetadata value : values) {
            array.add(innerClassJson(value));
        }
        return array;
    }

    private JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private void nullableString(JsonObject object, String field, String value) {
        if (value == null) {
            object.add(field, JsonNull.INSTANCE);
        } else {
            object.addProperty(field, value);
        }
    }
}
