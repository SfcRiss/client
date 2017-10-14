package com.runesuite.mapper

import com.runesuite.mapper.tree.*
import com.runesuite.mapper.extensions.Predicate
import java.lang.reflect.Modifier

abstract class StaticOrderMapper<T>(val position: Int) : Mapper<T>(), InstructionResolver<T> {

    override fun match(jar: Jar2): T {
        val n = position.takeUnless { it < 0 } ?: position * -1 - 1
        val instructions = jar.classes.asSequence()
                .flatMap { it.methods.asSequence() }
                .filter { Modifier.isStatic(it.access) }
                .flatMap { it.instructions }
        val relativeInstructions = if (position >= 0) {
            instructions
        } else {
            instructions.toList().asReversed().asSequence()
        }
        val instructionMatches = relativeInstructions.filter(predicate).toList()
        check(instructionMatches.isNotEmpty()) { "$this: No matches" }
        val inMethods = instructionMatches.map { it.method }.toSet()
        check (inMethods.size == 1) { "$this: Matches in multiple methods: $inMethods" }
        val instructionMatch = checkNotNull(instructionMatches.getOrNull(n)) { "$this: Invalid position: $position {$n}: $instructionMatches" }
        return resolve(instructionMatch)
    }

    abstract val predicate: Predicate<Instruction2>

    abstract class Class(position: Int) : StaticOrderMapper<Class2>(position), ElementMatcher.Class, InstructionResolver.Class

    abstract class Field(position: Int) : StaticOrderMapper<Field2>(position), ElementMatcher.Field, InstructionResolver.Field

    abstract class Method(position: Int) : StaticOrderMapper<Method2>(position), ElementMatcher.Method, InstructionResolver.Method
}