/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.jvm.compiler

import org.jetbrains.kotlin.descriptors.ClassOrPackageFragmentDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.load.kotlin.JvmPackagePartSource
import org.jetbrains.kotlin.load.kotlin.PackagePartProvider
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.ModuleAnnotationsResolver
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedClassDescriptor
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedMemberDescriptor
import org.jetbrains.kotlin.serialization.js.KotlinJavascriptPackageFragment
import org.jetbrains.kotlin.serialization.jvm.JvmProtoBuf

class CliModuleAnnotationsResolver : ModuleAnnotationsResolver {
    private val packagePartProviders = mutableListOf<PackagePartProvider>()

    fun addPackagePartProvider(packagePartProvider: PackagePartProvider) {
        packagePartProviders += packagePartProvider
    }

    override fun getAnnotationsOnContainingModule(descriptor: DeclarationDescriptor): List<ClassId> {
        val parent = DescriptorUtils.getParentOfType(descriptor, ClassOrPackageFragmentDescriptor::class.java, false) ?: return emptyList()

        getJsAnnotationsOnModule(descriptor, parent)?.let { return it }

        val moduleName = getJvmModuleName(descriptor, parent) ?: return emptyList()
        return packagePartProviders.flatMap { it.getAnnotationsOnBinaryModule(moduleName) }
    }

    private fun getJvmModuleName(descriptor: DeclarationDescriptor, parent: ClassOrPackageFragmentDescriptor): String? {
        when {
            parent is DeserializedClassDescriptor -> {
                val classProto = parent.classProto
                val nameResolver = parent.c.nameResolver
                return classProto.getExtension(JvmProtoBuf.classModuleName)
                    ?.takeIf { classProto.hasExtension(JvmProtoBuf.classModuleName) }
                    ?.let(nameResolver::getString)
                        ?: JvmAbi.DEFAULT_MODULE_NAME
            }
            descriptor is DeserializedMemberDescriptor -> {
                val source = descriptor.containerSource
                if (source is JvmPackagePartSource) {
                    val packageProto = source.packageProto
                    val nameResolver = source.nameResolver
                    return packageProto.getExtension(JvmProtoBuf.packageModuleName)
                        ?.takeIf { packageProto.hasExtension(JvmProtoBuf.packageModuleName) }
                        ?.let(nameResolver::getString)
                            ?: JvmAbi.DEFAULT_MODULE_NAME
                }
            }
        }

        return null
    }

    private fun getJsAnnotationsOnModule(descriptor: DeclarationDescriptor, parent: ClassOrPackageFragmentDescriptor): List<ClassId>? {
        val parentSource = (descriptor as? DeserializedMemberDescriptor)?.containerSource ?: parent.source
        return (parentSource as? KotlinJavascriptPackageFragment.JsContainerSource)?.annotations
    }
}
