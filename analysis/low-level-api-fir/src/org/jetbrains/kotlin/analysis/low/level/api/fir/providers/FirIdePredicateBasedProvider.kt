/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.low.level.api.fir.providers

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.kotlin.analysis.low.level.api.fir.file.builder.FirFileBuilder
import org.jetbrains.kotlin.analysis.low.level.api.fir.file.builder.ModuleFileCache
import org.jetbrains.kotlin.analysis.low.level.api.fir.util.getContainingFile
import org.jetbrains.kotlin.analysis.providers.KotlinAnnotationsResolver
import org.jetbrains.kotlin.analysis.providers.KotlinDeclarationProvider
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.caches.FirCache
import org.jetbrains.kotlin.fir.caches.createCache
import org.jetbrains.kotlin.fir.caches.firCachesFactory
import org.jetbrains.kotlin.fir.caches.getValue
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.extensions.AnnotationFqn
import org.jetbrains.kotlin.fir.extensions.FirPredicateBasedProvider
import org.jetbrains.kotlin.fir.extensions.FirRegisteredPluginAnnotations
import org.jetbrains.kotlin.fir.extensions.predicate.*
import org.jetbrains.kotlin.fir.extensions.registeredPluginAnnotations
import org.jetbrains.kotlin.fir.psi
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.visitors.FirVisitor
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.*

/**
 * PSI index based implementation of [FirPredicateBasedProvider].
 */
internal class FirIdePredicateBasedProvider(
    private val session: FirSession,
    private val cache: ModuleFileCache,
    private val firFileBuilder: FirFileBuilder,
    private val annotationsResolver: KotlinAnnotationsResolver,
    private val declarationProvider: KotlinDeclarationProvider,
) : FirPredicateBasedProvider() {

    private val registeredPluginAnnotations: FirRegisteredPluginAnnotations
        get() = session.registeredPluginAnnotations

    private val declarationsCache: FirCache<FirFile, FirIdeDeclarationsCache, Nothing?> =
        session.firCachesFactory.createCache { firFile -> FirIdeDeclarationsCache.createForFile(firFile) }

    override fun getSymbolsByPredicate(predicate: DeclarationPredicate): List<FirBasedSymbol<*>> {
        val annotations = registeredPluginAnnotations.getAnnotationsForPredicate(predicate)
        val annotatedDeclarations = annotations
            .asSequence()
            .flatMap { annotationsResolver.declarationsByAnnotation(ClassId.topLevel(it)) }
            .toSet()

        return annotatedDeclarations
            .asSequence()
            .mapNotNull { it.findFirDeclaration() }
            .filter { matches(predicate, it) }
            .map { it.symbol }
            .toList()
    }

    private fun KtElement.findFirDeclaration(): FirDeclaration? {
        if (this !is KtClassLikeDeclaration &&
            this !is KtNamedFunction &&
            this !is KtConstructor<*> &&
            this !is KtProperty
        ) return null

        val fileDeclarations = declarationsCache.getValue(containingKtFile.getOrBuildFirFile())
        return fileDeclarations.getDeclarationByPsi(this)
    }

    private fun KtFile.getOrBuildFirFile(): FirFile {
        return firFileBuilder.buildRawFirFileWithCaching(this, cache, preferLazyBodies = true)
    }

    override fun getOwnersOfDeclaration(declaration: FirDeclaration): List<FirBasedSymbol<*>>? {
        val firFile = declaration.getContainingFile() ?: return null
        val fileCache = declarationsCache.getValue(firFile)

        return fileCache.getOwners(declaration)
    }

    override fun fileHasPluginAnnotations(file: FirFile): Boolean {
        val targetKtFile = file.psi as? KtFile ?: return false
        val pluginAnnotations = registeredPluginAnnotations.annotations

        return pluginAnnotations.any {
            val annotationId = ClassId.topLevel(it)
            val markedDeclarations = annotationsResolver.declarationsByAnnotation(annotationId)

            targetKtFile in markedDeclarations
        }
    }

    override fun matches(predicate: DeclarationPredicate, declaration: FirDeclaration): Boolean {
        return predicate.accept(matcher, declaration)
    }

    private val matcher: Matcher = Matcher()

    inner class Matcher : DeclarationPredicateVisitor<Boolean, FirDeclaration>() {
        override fun visitPredicate(predicate: DeclarationPredicate, data: FirDeclaration): Boolean {
            error("Should not be called")
        }

        override fun visitAny(predicate: DeclarationPredicate.Any, data: FirDeclaration): Boolean {
            return true
        }

        override fun visitAnd(predicate: DeclarationPredicate.And, data: FirDeclaration): Boolean {
            return predicate.a.accept(this, data) && predicate.b.accept(this, data)
        }

        override fun visitOr(predicate: DeclarationPredicate.Or, data: FirDeclaration): Boolean {
            return predicate.a.accept(this, data) || predicate.b.accept(this, data)
        }

        override fun visitAnnotatedWith(predicate: AnnotatedWith, data: FirDeclaration): Boolean {
            return annotationsOnDeclaration(data).any { it in predicate.annotations }
        }

        override fun visitUnderAnnotatedWith(predicate: UnderAnnotatedWith, data: FirDeclaration): Boolean {
            return annotationsOnOuterDeclarations(data).any { it in predicate.annotations }
        }

        override fun visitAnnotatedWithMeta(predicate: AnnotatedWithMeta, data: FirDeclaration): Boolean {
            return metaAnnotationsOnDeclaration(data).any { it in predicate.metaAnnotations }
        }

        override fun visitUnderMetaAnnotated(predicate: UnderMetaAnnotated, data: FirDeclaration): Boolean {
            return metaAnnotationsOnOuterDeclarations(data).any { it in predicate.metaAnnotations }
        }
    }

    private fun annotationsOnDeclaration(declaration: FirDeclaration): List<AnnotationFqn> {
        if (declaration.annotations.isEmpty()) return emptyList()

        val firResolvedAnnotations = declaration.annotations
            .asSequence()
            .mapNotNull { it.annotationTypeRef as? FirResolvedTypeRef }
            .mapNotNull { it.type.classId }
            .map { it.asSingleFqName() }
            .toList()

        if (firResolvedAnnotations.isNotEmpty()) return firResolvedAnnotations

        val psiDeclaration = declaration.psi as? KtElement ?: return emptyList()
        return annotationsResolver.annotationsOnDeclaration(psiDeclaration).map { it.asSingleFqName() }
    }

    private fun metaAnnotationsOnDeclaration(declaration: FirDeclaration): List<AnnotationFqn> {
        val directAnnotations = annotationsOnDeclaration(declaration)
        val metaAnnotations = directAnnotations
            .asSequence()
            .mapNotNull { declarationProvider.getClassesByClassId(ClassId.topLevel(it)).singleOrNull() }
            .flatMap { annotationsResolver.annotationsOnDeclaration(it) }
            .toSet()

        return metaAnnotations.map { it.asSingleFqName() }
    }

    private fun annotationsOnOuterDeclarations(declaration: FirDeclaration): List<AnnotationFqn> {
        return getOwnersOfDeclaration(declaration)?.flatMap { annotationsOnDeclaration(it.fir) }.orEmpty()
    }

    private fun metaAnnotationsOnOuterDeclarations(declaration: FirDeclaration): List<AnnotationFqn> {
        return getOwnersOfDeclaration(declaration)?.flatMap { metaAnnotationsOnDeclaration(it.fir) }.orEmpty()
    }
}

private class FirIdeDeclarationsCache(
    private val psiToFir: Map<KtElement, FirDeclaration>,
    private val declarationToOwners: Map<FirDeclaration, List<FirBasedSymbol<*>>>
) {
    fun getDeclarationByPsi(key: KtElement): FirDeclaration? = psiToFir[key]

    fun getOwners(declaration: FirDeclaration): List<FirBasedSymbol<*>>? = declarationToOwners[declaration]

    companion object {
        fun createForFile(file: FirFile): FirIdeDeclarationsCache {
            val declarationToOwners = hashMapOf<FirDeclaration, List<FirBasedSymbol<*>>>()
            val psiToFir = hashMapOf<KtElement, FirDeclaration>()

            file.forEachElementWithContainers { element, owners ->
                if (element !is FirDeclaration) return@forEachElementWithContainers

                declarationToOwners[element] = owners

                val psiDeclaration = element.psi
                if (psiDeclaration is KtElement) {
                    // FIXME we actually have a problem with KtFakeSourceElement sources
                    psiToFir.putIfAbsent(psiDeclaration, element)
                }
            }

            return FirIdeDeclarationsCache(psiToFir, declarationToOwners)
        }
    }
}

/**
 * Walks over every [FirElement] in [this] file and invokes [saveDeclaration] on it, passing each element and the list of its containing
 * declarations (like file, classes, functions/properties and so on).
 */
private inline fun FirFile.forEachElementWithContainers(
    crossinline saveDeclaration: (element: FirElement, owners: List<FirBasedSymbol<*>>) -> Unit
) {
    val declarationsCollector = object : FirVisitor<Unit, PersistentList<FirBasedSymbol<*>>>() {
        override fun visitElement(element: FirElement, data: PersistentList<FirBasedSymbol<*>>) {
            if (element is FirDeclaration) {
                saveDeclaration(element, data)
            }

            element.acceptChildren(
                visitor = this,
                data = if (element is FirDeclaration) data.add(element.symbol) else data
            )
        }
    }

    accept(declarationsCollector, persistentListOf())
}
