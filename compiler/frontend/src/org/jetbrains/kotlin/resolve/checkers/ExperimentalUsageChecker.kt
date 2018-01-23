/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.resolve.checkers

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.config.AnalysisFlag
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.*
import org.jetbrains.kotlin.resolve.calls.checkers.CallChecker
import org.jetbrains.kotlin.resolve.calls.checkers.CallCheckerContext
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.constants.ArrayValue
import org.jetbrains.kotlin.resolve.constants.EnumValue
import org.jetbrains.kotlin.resolve.constants.KClassValue
import org.jetbrains.kotlin.resolve.descriptorUtil.annotationClass
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import org.jetbrains.kotlin.utils.SmartSet
import org.jetbrains.kotlin.utils.addIfNotNull

class ExperimentalUsageChecker(project: Project) : CallChecker {
    private val moduleAnnotationsResolver = ModuleAnnotationsResolver.getInstance(project)

    private data class Experimentality(val annotationFqName: FqName, val severity: Severity, val scope: Scope) {
        enum class Severity { WARNING, ERROR }
        enum class Scope { SOURCE_ONLY, BINARY }

        companion object {
            val DEFAULT_SEVERITY = Severity.ERROR
            val DEFAULT_SCOPE = Scope.BINARY
        }
    }

    override fun check(resolvedCall: ResolvedCall<*>, reportOn: PsiElement, context: CallCheckerContext) {
        // TODO: ensure reportOn is never a synthetic element
        checkExperimental(resolvedCall.resultingDescriptor, reportOn, context, moduleAnnotationsResolver)
    }

    companion object {
        val EXPERIMENTAL_FQ_NAME = FqName("kotlin.Experimental")
        private val USE_EXPERIMENTAL_FQ_NAME = FqName("kotlin.UseExperimental")
        private val USE_EXPERIMENTAL_ANNOTATION_CLASS = Name.identifier("annotationClass")

        private val LEVEL = Name.identifier("level")
        private val WARNING_LEVEL = Name.identifier("WARNING")
        private val ERROR_LEVEL = Name.identifier("ERROR")

        private val SCOPE = Name.identifier("scope")
        private val SOURCE_ONLY_SCOPE = Name.identifier("SOURCE_ONLY")
        private val BINARY_SCOPE = Name.identifier("BINARY")

        private fun checkExperimental(
            descriptor: DeclarationDescriptor,
            element: PsiElement,
            context: CheckerContext,
            moduleAnnotationsResolver: ModuleAnnotationsResolver
        ) {
            val experimentalities = descriptor.loadExperimentalities(moduleAnnotationsResolver)
            if (experimentalities.isNotEmpty()) {
                checkExperimental(
                    experimentalities, element, context.trace.bindingContext, context.languageVersionSettings,
                    descriptor.module == context.moduleDescriptor
                ) { annotationFqName, severity, isBodyUsageOfSourceOnlyExperimentality ->
                    val diagnostic = when (severity) {
                        Experimentality.Severity.WARNING -> Errors.EXPERIMENTAL_API_USAGE
                        Experimentality.Severity.ERROR -> Errors.EXPERIMENTAL_API_USAGE_ERROR
                    }
                    context.trace.report(diagnostic.on(element, annotationFqName, isBodyUsageOfSourceOnlyExperimentality))
                }
            }
        }

        private fun checkExperimental(
            experimentalities: Collection<Experimentality>,
            element: PsiElement,
            bindingContext: BindingContext,
            languageVersionSettings: LanguageVersionSettings,
            isUsageInSameModule: Boolean,
            report: (annotationFqName: FqName, severity: Experimentality.Severity, isBodyUsageOfSourceOnlyExperimentality: Boolean) -> Unit
        ) {
            val isBodyUsage: Boolean by lazy(LazyThreadSafetyMode.NONE) { element.isBodyUsage() }
            val isBodyUsageInSameModule = isUsageInSameModule && element.isBodyUsage(allowInline = false)

            for ((annotationFqName, severity, scope) in experimentalities) {
                val isBodyUsageOfSourceOnlyExperimentality =
                    scope == Experimentality.Scope.SOURCE_ONLY && isBodyUsage

                val isExperimentalityAccepted =
                        isBodyUsageInSameModule ||
                        (isBodyUsageOfSourceOnlyExperimentality &&
                         element.hasContainerAnnotatedWithUseExperimental(annotationFqName, bindingContext, languageVersionSettings)) ||
                        element.propagates(annotationFqName, bindingContext, languageVersionSettings)

                if (!isExperimentalityAccepted) {
                    report(annotationFqName, severity, isBodyUsageOfSourceOnlyExperimentality)
                }
            }
        }

        private fun DeclarationDescriptor.loadExperimentalities(
            moduleAnnotationsResolver: ModuleAnnotationsResolver
        ): Set<Experimentality> {
            val result = SmartSet.create<Experimentality>()

            for (annotation in annotations) {
                result.addIfNotNull(annotation.annotationClass?.loadExperimentalityForMarkerAnnotation())
            }

            val container = containingDeclaration
            if (container is ClassDescriptor && this !is ConstructorDescriptor) {
                for (annotation in container.annotations) {
                    result.addIfNotNull(annotation.annotationClass?.loadExperimentalityForMarkerAnnotation())
                }
            }

            for (moduleAnnotationClassId in moduleAnnotationsResolver.getAnnotationsOnContainingModule(this)) {
                val annotationClass = module.findClassAcrossModuleDependencies(moduleAnnotationClassId)
                result.addIfNotNull(annotationClass?.loadExperimentalityForMarkerAnnotation())
            }

            return result
        }

        private fun ClassDescriptor.loadExperimentalityForMarkerAnnotation(): Experimentality? {
            val experimental = annotations.findAnnotation(EXPERIMENTAL_FQ_NAME) ?: return null

            val severity = when ((experimental.allValueArguments[LEVEL] as? EnumValue)?.enumEntryName) {
                WARNING_LEVEL -> Experimentality.Severity.WARNING
                ERROR_LEVEL -> Experimentality.Severity.ERROR
                else -> Experimentality.DEFAULT_SEVERITY
            }

            val scope = when ((experimental.allValueArguments[SCOPE] as? EnumValue)?.enumEntryName) {
                SOURCE_ONLY_SCOPE -> Experimentality.Scope.SOURCE_ONLY
                BINARY_SCOPE -> Experimentality.Scope.BINARY
                else -> Experimentality.DEFAULT_SCOPE
            }

            return Experimentality(fqNameSafe, severity, scope)
        }

        // Returns true if this element appears in the body of some function and is not visible in any non-local declaration signature.
        // If that's the case, one can opt-in to using the corresponding experimental API by annotating this element (or any of its
        // enclosing declarations) with @UseExperimental(X::class), not requiring propagation of the experimental annotation to the call sites.
        // (Note that this is allowed only if X's scope is SOURCE_ONLY.)
        private fun PsiElement.isBodyUsage(allowInline: Boolean = true): Boolean {
            var element = this
            while (true) {
                val parent = element.parent ?: return false

                if (element == (parent as? KtDeclarationWithBody)?.bodyExpression?.takeIf { allowInline || !parent.isInline } ||
                    element == (parent as? KtDeclarationWithInitializer)?.initializer ||
                    element == (parent as? KtClassInitializer)?.body ||
                    element == (parent as? KtParameter)?.defaultValue ||
                    element == (parent as? KtSuperTypeCallEntry)?.valueArgumentList ||
                    element == (parent as? KtDelegatedSuperTypeEntry)?.delegateExpression ||
                    element == (parent as? KtPropertyDelegate)?.expression) return true

                if (element is KtFile) return false
                element = parent
            }
        }

        private val PsiElement.isInline: Boolean
            get() = when (this) {
                is KtFunction -> hasModifier(KtTokens.INLINE_KEYWORD)
                is KtPropertyAccessor -> hasModifier(KtTokens.INLINE_KEYWORD) || property.hasModifier(KtTokens.INLINE_KEYWORD)
                else -> false
            }

        // Checks whether any of the non-local enclosing declarations is annotated with annotationFqName, effectively requiring
        // propagation for the experimental annotation to the call sites
        private fun PsiElement.propagates(
            annotationFqName: FqName,
            bindingContext: BindingContext,
            languageVersionSettings: LanguageVersionSettings
        ): Boolean {
            var element = this
            while (true) {
                if (element is KtDeclaration) {
                    val descriptor = bindingContext.get(BindingContext.DECLARATION_TO_DESCRIPTOR, element)
                    if (descriptor != null && !DescriptorUtils.isLocal(descriptor) &&
                        descriptor.annotations.hasAnnotation(annotationFqName)) return true
                }

                if (element is KtFile) break
                element = element.parent ?: break
            }

            return annotationFqName.asString() in languageVersionSettings.getFlag(AnalysisFlag.experimental)
        }

        // Checks whether there's an element lexically above the tree, that is annotated with `@UseExperimental(X::class)`
        // where annotationFqName is the FQ name of X
        private fun PsiElement.hasContainerAnnotatedWithUseExperimental(
            annotationFqName: FqName,
            bindingContext: BindingContext,
            languageVersionSettings: LanguageVersionSettings
        ): Boolean {
            var element = this
            while (true) {
                if (element is KtAnnotated && element.annotationEntries.any { entry ->
                    bindingContext.get(BindingContext.ANNOTATION, entry)?.isUseExperimental(annotationFqName) == true
                }) return true

                if (element is KtFile) break
                element = element.parent ?: break
            }

            return annotationFqName.asString() in languageVersionSettings.getFlag(AnalysisFlag.useExperimental)
        }

        private fun AnnotationDescriptor.isUseExperimental(annotationFqName: FqName): Boolean {
            if (fqName != USE_EXPERIMENTAL_FQ_NAME) return false

            val annotationClasses = allValueArguments[USE_EXPERIMENTAL_ANNOTATION_CLASS]
            return annotationClasses is ArrayValue && annotationClasses.value.any { annotationClass ->
                (annotationClass as? KClassValue)?.value?.constructor?.declarationDescriptor?.fqNameSafe == annotationFqName
            }
        }

        fun checkCompilerArguments(
            module: ModuleDescriptor,
            languageVersionSettings: LanguageVersionSettings,
            reportError: (String) -> Unit,
            reportWarning: (String) -> Unit
        ) {
            // Ideally, we should run full resolution (with all classifier usage checkers) on classifiers, used in "-Xexperimental" and
            // "-Xuse-experimental" arguments. However, it's not easy to do this. This should be solved in the future with the support of
            // module annotations. For now, we only check deprecations because this is needed to correctly retire unneeded compiler arguments.
            val deprecationResolver = DeprecationResolver(LockBasedStorageManager(), languageVersionSettings)

            fun checkAnnotation(fqName: String, allowBinaryScope: Boolean): Boolean {
                val descriptor = module.resolveClassByFqName(FqName(fqName), NoLookupLocation.FOR_NON_TRACKED_SCOPE)
                val experimentality = descriptor?.loadExperimentalityForMarkerAnnotation()
                val message = when {
                    descriptor == null ->
                        "Experimental API marker $fqName is unresolved. " +
                        "Please make sure it's present in the module dependencies"
                    experimentality == null ->
                        "Class $fqName is not an experimental API marker annotation"
                    !allowBinaryScope && experimentality.scope == Experimentality.Scope.BINARY ->
                        "Experimental API marker $fqName has binary scope, therefore it can't be used with -Xuse-experimental"
                    else -> {
                        for (deprecation in deprecationResolver.getDeprecations(descriptor)) {
                            val report = when (deprecation.deprecationLevel) {
                                DeprecationLevelValue.WARNING -> reportWarning
                                DeprecationLevelValue.ERROR, DeprecationLevelValue.HIDDEN -> reportError
                            }
                            report("Experimental API marker $fqName is deprecated" + deprecation.message?.let { ". $it" }.orEmpty())
                        }
                        return true
                    }
                }

                reportError(message)

                return false
            }

            val validExperimental =
                languageVersionSettings.getFlag(AnalysisFlag.experimental).filter { checkAnnotation(it, allowBinaryScope = true) }
            val validUseExperimental =
                languageVersionSettings.getFlag(AnalysisFlag.useExperimental).filter { checkAnnotation(it, allowBinaryScope = false) }

            for (fqName in validExperimental.intersect(validUseExperimental)) {
                reportError("'-Xuse-experimental=$fqName' has no effect because '-Xexperimental=$fqName' is used")
            }
        }
    }

    class ClassifierUsage(project: Project) : ClassifierUsageChecker {
        private val moduleAnnotationsResolver = ModuleAnnotationsResolver.getInstance(project)

        override fun check(targetDescriptor: ClassifierDescriptor, element: PsiElement, context: ClassifierUsageCheckerContext) {
            checkExperimental(targetDescriptor, element, context, moduleAnnotationsResolver)
        }
    }

    class Overrides(project: Project) : DeclarationChecker {
        private val moduleAnnotationsResolver = ModuleAnnotationsResolver.getInstance(project)

        override fun check(declaration: KtDeclaration, descriptor: DeclarationDescriptor, context: DeclarationCheckerContext) {
            if (descriptor !is CallableMemberDescriptor) return

            val experimentalOverridden = descriptor.overriddenDescriptors.flatMap { member ->
                member.loadExperimentalities(moduleAnnotationsResolver).map { experimentality -> experimentality to member }
            }.toMap()

            for ((experimentality, member) in experimentalOverridden) {
                checkExperimental(
                    listOf(experimentality), declaration, context.trace.bindingContext, context.languageVersionSettings,
                    isUsageInSameModule = false
                ) { annotationFqName, severity, _ ->
                    val diagnostic = when (severity) {
                        Experimentality.Severity.WARNING -> Errors.EXPERIMENTAL_OVERRIDE
                        Experimentality.Severity.ERROR -> Errors.EXPERIMENTAL_OVERRIDE_ERROR
                    }
                    val reportOn = (declaration as? KtNamedDeclaration)?.nameIdentifier ?: declaration
                    context.trace.report(diagnostic.on(reportOn, annotationFqName, member.containingDeclaration))
                }
            }
        }
    }

    object ExperimentalDeclarationChecker : AdditionalAnnotationChecker {
        private val wrongTargetsForExperimentalAnnotations = setOf(KotlinTarget.EXPRESSION, KotlinTarget.FILE)

        override fun checkEntries(entries: List<KtAnnotationEntry>, actualTargets: List<KotlinTarget>, trace: BindingTrace) {
            var isAnnotatedWithExperimental = false

            for (entry in entries) {
                val annotation = trace.bindingContext.get(BindingContext.ANNOTATION, entry)
                if (annotation?.fqName == USE_EXPERIMENTAL_FQ_NAME) {
                    val annotationClasses =
                        (annotation.allValueArguments[USE_EXPERIMENTAL_ANNOTATION_CLASS] as? ArrayValue)?.value ?: continue
                    if (annotationClasses.isEmpty()) {
                        trace.report(Errors.USE_EXPERIMENTAL_WITHOUT_ARGUMENTS.on(entry))
                        continue
                    }
                    for (annotationClass in annotationClasses) {
                        val classDescriptor =
                            (annotationClass as? KClassValue)?.value?.constructor?.declarationDescriptor as? ClassDescriptor ?: continue
                        val experimentality = classDescriptor.loadExperimentalityForMarkerAnnotation()
                        if (experimentality == null) {
                            trace.report(Errors.USE_EXPERIMENTAL_ARGUMENT_IS_NOT_MARKER.on(entry, classDescriptor.fqNameSafe))
                        } else if (experimentality.scope == Experimentality.Scope.BINARY) {
                            trace.report(Errors.USE_EXPERIMENTAL_ARGUMENT_HAS_BINARY_SCOPE.on(entry, experimentality.annotationFqName))
                        }
                    }
                }

                if (annotation?.fqName == EXPERIMENTAL_FQ_NAME) {
                    isAnnotatedWithExperimental = true
                }
            }

            if (isAnnotatedWithExperimental) {
                val resolvedEntries = entries.associate { entry -> entry to trace.bindingContext.get(BindingContext.ANNOTATION, entry) }
                for ((entry, descriptor) in resolvedEntries) {
                    if (descriptor != null && descriptor.fqName == KotlinBuiltIns.FQ_NAMES.target) {
                        val allowedTargets = AnnotationChecker.loadAnnotationTargets(descriptor) ?: continue
                        val wrongTargets = allowedTargets.intersect(wrongTargetsForExperimentalAnnotations)
                        if (wrongTargets.isNotEmpty()) {
                            trace.report(
                                Errors.EXPERIMENTAL_ANNOTATION_WITH_WRONG_TARGET.on(
                                    entry, wrongTargets.joinToString(transform = KotlinTarget::description)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
