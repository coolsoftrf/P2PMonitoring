package ru.coolsoft.annotations.processor;

import com.sun.source.tree.Tree;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.model.JavacElements;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;

import ru.coolsoft.annotations.ById;
import ru.coolsoft.annotations.ByIdDefault;
import ru.coolsoft.annotations.ByIdRefField;


@SupportedAnnotationTypes({
        "ru.coolsoft.annotations.ById",
        "ru.coolsoft.annotations.ByIdRefField",
        "ru.coolsoft.annotations.ByIdDefault"
})
@SupportedSourceVersion(SourceVersion.RELEASE_11)
public class AnnotationProcessor extends AbstractProcessor {

    private static final String BY_ID_METHOD_MANE = "byId";
    private static final String ID_PARAM_MANE = "id";

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (annotations.isEmpty()) {
            return false;
        }

        prepareElements(roundEnv, ((enumeration, defaultConstant, refField) -> {
            List<VariableElement> cases = new ArrayList<>();
            for (Element element : enumeration.getEnclosedElements()) {
                if (element instanceof VariableElement
                        && element.asType().getKind().equals(enumeration.asType().getKind())) {
                    cases.add((VariableElement) element);
                }
            }
            if (cases.size() == 0) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Missing enum values", enumeration);
                return;
            }

            final Context context = ((JavacProcessingEnvironment) processingEnv).getContext();
            Trees trees = Trees.instance(processingEnv);
            TreeMaker treeMaker = TreeMaker.instance(context);
            JavacElements elements = JavacElements.instance(context);
            JCTree.JCClassDecl enumTree = (JCTree.JCClassDecl) trees.getTree(enumeration);

            //pos opt
            treeMaker.at(((JCTree.JCVariableDecl) trees.getTree(refField)).pos);
            JCTree.JCVariableDecl idParam = treeMaker.VarDef(
                    treeMaker.Modifiers(Flags.PARAMETER),
                    elements.getName(ID_PARAM_MANE),
                    treeMaker.Type((Type) refField.asType()),
                    null
            );

            List<JCTree.JCCase> jcCases = new ArrayList<>(cases.size());
            for (VariableElement value : cases) {
                if (value != defaultConstant) {
                    Tree valTree = trees.getTree(value);
                    jcCases.add(treeMaker.Case(
                            ((JCTree.JCNewClass) ((JCTree.JCVariableDecl)
                                    valTree)
                                    .getInitializer())
                                    .getArguments().last(),
                            com.sun.tools.javac.util.List.of(
                                    treeMaker.Return(treeMaker.Ident(((JCTree.JCVariableDecl) valTree)))
                            )
                    ));
                }
            }
            jcCases.add(treeMaker.Case(null, com.sun.tools.javac.util.List.of(
                    treeMaker.Return(treeMaker.Ident((JCTree.JCVariableDecl) trees.getTree(defaultConstant)))
            )));

            //pos opt
            //idParam.pos = ((JCTree.JCVariableDecl)trees.getTree(refField)).pos;
            JCTree.JCMethodDecl method = treeMaker.MethodDef(
                    treeMaker.Modifiers(Flags.PUBLIC | Flags.STATIC),
                    elements.getName(BY_ID_METHOD_MANE),
                    treeMaker.Type((Type) enumeration.asType()),
                    com.sun.tools.javac.util.List.nil(),
                    com.sun.tools.javac.util.List.of(idParam),
                    com.sun.tools.javac.util.List.nil(),
                    treeMaker.Block(0, com.sun.tools.javac.util.List.of(
                            treeMaker.Switch(treeMaker.Ident(idParam.getName()), com.sun.tools.javac.util.List.from(jcCases))
                    )),
                    null
            );

            enumTree.defs = enumTree.defs.append(method);
        }));

        return false;
    }

    private void prepareElements(RoundEnvironment roundEnv, OkConsumer okConsumer) {
        final Set<? extends Element> types = roundEnv.getElementsAnnotatedWith(ById.class);
        if (types.isEmpty()) {
            return;
        }
        for (Element enumeration : types) {
            final List<? extends Element> fields = enumeration.getEnclosedElements();
            VariableElement defaultConstant = null;
            VariableElement refField = null;
            for (Element el : fields) {
                if (el.getAnnotationsByType(ByIdDefault.class).length > 0) {
                    defaultConstant = (VariableElement) el;
                }
                if (el.getAnnotationsByType(ByIdRefField.class).length > 0) {
                    refField = (VariableElement) el;
                }
            }
            if (defaultConstant == null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Missing @ByIdDefault field", enumeration);
                return;
            }
            if (refField == null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Missing @ByIdRefField field", enumeration);
                return;
            }

            for (Element element : enumeration.getEnclosedElements()) {
                if (element instanceof ExecutableElement) {
                    ExecutableElement method = (ExecutableElement) element;
                    if (method.getSimpleName().contentEquals(BY_ID_METHOD_MANE)
                            && method.getReturnType().getKind().equals(enumeration.asType().getKind())) {
                        List<? extends VariableElement> parameters = method.getParameters();
                        if (parameters.size() == 1
                                && parameters.get(0).asType().getKind().equals(refField.asType().getKind())) {
                            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                                    String.format("Method %s %s(%s) already exists. Ignoring @ById* annotations",
                                            enumeration.getSimpleName(),
                                            BY_ID_METHOD_MANE,
                                            refField.getSimpleName()),
                                    method);
                            return;
                        }
                    }
                }
            }

            okConsumer.consume((TypeElement) enumeration, defaultConstant, refField);
        }
    }

    private interface OkConsumer {
        void consume(TypeElement enumeration, VariableElement defaultConstant, VariableElement refField);
    }
}