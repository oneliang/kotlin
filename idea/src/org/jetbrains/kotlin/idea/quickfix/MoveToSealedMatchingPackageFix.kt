/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.quickfix

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiManager
import com.intellij.refactoring.PackageWrapper
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesUtil
import org.jetbrains.kotlin.descriptors.containingPackage
import org.jetbrains.kotlin.diagnostics.Diagnostic
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.refactoring.move.changePackage.getDirToMoveTo
import org.jetbrains.kotlin.idea.references.resolveMainReferenceToDescriptors
import org.jetbrains.kotlin.idea.roots.collectKotlinAwareDestinationSourceRoots
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils

class MoveToSealedMatchingPackageFix(element: KtTypeReference) : KotlinQuickFixAction<KtTypeReference>(element) {

    override fun invoke(project: Project, editor: Editor?, file: KtFile) {
        val typeReference = element ?: return
        val ktUserType = typeReference.typeElement as? KtUserType ?: return
        val ktNameReferenceExpression = ktUserType.referenceExpression as? KtNameReferenceExpression ?: return
        val declDescriptor = ktNameReferenceExpression.resolveMainReferenceToDescriptors().singleOrNull() ?: return
        val ktClassInQuestion = DescriptorToSourceUtils.getSourceFromDescriptor(declDescriptor) as? KtClass ?: return

        val projectFileIndex = ProjectFileIndex.getInstance(project)
        val module = projectFileIndex.getModuleForFile(ktClassInQuestion.containingFile.virtualFile) ?: return
        val packageFqName = declDescriptor.containingPackage() ?: return

        val sourceRoots = module.collectKotlinAwareDestinationSourceRoots()
        val packageWrapper = PackageWrapper(PsiManager.getInstance(project), packageFqName.asString())

        val targetDirectory = getDirToMoveTo(file, sourceRoots, packageWrapper, project) ?: return

        runWriteAction {
            MoveFilesOrDirectoriesUtil.doMoveFile(file, targetDirectory)
            // packageDirective is null for scripts only
            file.packageDirective!!.fqName = ktClassInQuestion.containingKtFile.packageDirective!!.fqName
        }
    }

    override fun startInWriteAction(): Boolean {
        return false
    }

    override fun getText(): String {
        val ktTypeReference = element ?: return ""
        val referencedName = (ktTypeReference.typeElement as KtUserType).referenceExpression?.getReferencedName() ?: return ""
        return KotlinBundle.message("fix.move.to.sealed.text", referencedName)
    }

    override fun getFamilyName(): String {
        return KotlinBundle.message("fix.move.to.sealed.family")
    }

    companion object : KotlinSingleIntentionActionFactory() {
        override fun createAction(diagnostic: Diagnostic): MoveToSealedMatchingPackageFix? {
            val annotationEntry = diagnostic.psiElement as? KtTypeReference ?: return null
            return MoveToSealedMatchingPackageFix(annotationEntry)
        }
    }
}