package com.dbflow5.processor.definition

import com.dbflow5.annotation.ForeignKey
import com.dbflow5.annotation.ManyToMany
import com.dbflow5.annotation.PrimaryKey
import com.dbflow5.annotation.Table
import com.dbflow5.processor.ProcessorManager
import com.dbflow5.processor.utils.annotation
import com.dbflow5.processor.utils.extractTypeNameFromAnnotation
import com.dbflow5.processor.utils.isNullOrEmpty
import com.dbflow5.processor.utils.lower
import com.dbflow5.processor.utils.toClassName
import com.dbflow5.processor.utils.toTypeElement
import com.grosner.kpoet.L
import com.grosner.kpoet.`@`
import com.grosner.kpoet.`fun`
import com.grosner.kpoet.`return`
import com.grosner.kpoet.field
import com.grosner.kpoet.final
import com.grosner.kpoet.member
import com.grosner.kpoet.modifiers
import com.grosner.kpoet.param
import com.grosner.kpoet.public
import com.grosner.kpoet.statement
import com.squareup.javapoet.AnnotationSpec
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import javax.lang.model.element.TypeElement

/**
 * Description: Generates the Model class that is used in a many to many.
 */
class ManyToManyDefinition(element: TypeElement, processorManager: ProcessorManager,
                           manyToMany: ManyToMany = element.annotation()!!)
    : BaseDefinition(element, processorManager) {

    var databaseTypeName: TypeName? = null

    private var referencedTable: TypeName
    private var generateAutoIncrement: Boolean = false
    private var sameTableReferenced: Boolean = false
    private val generatedTableClassName = manyToMany.generatedTableClassName
    private var saveForeignKeyModels: Boolean = false
    private val thisColumnName = manyToMany.thisTableColumnName
    private val referencedColumnName = manyToMany.referencedTableColumnName

    init {
        referencedTable = manyToMany.extractTypeNameFromAnnotation { it.referencedTable }
        generateAutoIncrement = manyToMany.generateAutoIncrement
        saveForeignKeyModels = manyToMany.saveForeignKeyModels

        sameTableReferenced = referencedTable == elementTypeName

        databaseTypeName = element.extractTypeNameFromAnnotation<Table> { it.database }
        if (!thisColumnName.isNullOrEmpty() && !referencedColumnName.isNullOrEmpty()
            && thisColumnName == referencedColumnName) {
            manager.logError(ManyToManyDefinition::class, "The thisTableColumnName and referenceTableColumnName cannot be the same")
        }
    }

    fun prepareForWrite() {
        val databaseDefinition = manager.getDatabaseHolderDefinition(databaseTypeName)?.databaseDefinition
        if (databaseDefinition == null) {
            manager.logError("DatabaseDefinition was null for : $elementName")
        } else {
            if (generatedTableClassName.isNullOrEmpty()) {
                val referencedOutput = referencedTable.toTypeElement(manager).toClassName(manager)
                setOutputClassName(databaseDefinition.classSeparator + referencedOutput?.simpleName())
            } else {
                setOutputClassNameFull(generatedTableClassName)
            }
        }
    }

    override fun onWriteDefinition(typeBuilder: TypeSpec.Builder) {
        typeBuilder.apply {
            addAnnotation(AnnotationSpec.builder(Table::class.java)
                .addMember("database", "\$T.class", databaseTypeName).build())

            val referencedDefinition = manager.getTableDefinition(databaseTypeName, referencedTable)
            val selfDefinition = manager.getTableDefinition(databaseTypeName, elementTypeName)

            if (generateAutoIncrement) {
                addField(field(`@`(PrimaryKey::class) { this["autoincrement"] = "true" }, TypeName.LONG, "_id").build())

                `fun`(TypeName.LONG, "getId") {
                    modifiers(public, final)
                    `return`("_id")
                }
            }

            referencedDefinition?.let { appendColumnDefinitions(this, it, 0, referencedColumnName) }
            selfDefinition?.let { appendColumnDefinitions(this, it, 1, thisColumnName) }
        }
    }

    override val extendsClass: TypeName?
        get() = com.dbflow5.processor.ClassNames.BASE_MODEL

    private fun appendColumnDefinitions(typeBuilder: TypeSpec.Builder,
                                        referencedDefinition: TableDefinition, index: Int, optionalName: String) {
        var fieldName = referencedDefinition.elementName.lower()
        if (sameTableReferenced) {
            fieldName += index.toString()
        }
        // override with the name (if specified)
        if (!optionalName.isNullOrEmpty()) {
            fieldName = optionalName
        }

        typeBuilder.apply {
            `field`(referencedDefinition.elementClassName!!, fieldName) {
                if (!generateAutoIncrement) {
                    `@`(PrimaryKey::class)
                }
                `@`(ForeignKey::class) { member("saveForeignKeyModel", saveForeignKeyModels.toString()) }
            }
            `fun`(referencedDefinition.elementClassName!!, "get${fieldName.capitalize()}") {
                modifiers(public, final)
                `return`(fieldName.L)
            }
            `fun`(TypeName.VOID, "set${fieldName.capitalize()}",
                param(referencedDefinition.elementClassName!!, "param")) {
                modifiers(public, final)
                statement("$fieldName = param")
            }
        }
    }
}