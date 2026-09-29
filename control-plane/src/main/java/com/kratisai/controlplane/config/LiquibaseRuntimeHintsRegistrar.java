package com.kratisai.controlplane.config;

import java.util.List;
import liquibase.change.AbstractChange;
import liquibase.change.AbstractSQLChange;
import liquibase.change.AbstractTableChange;
import liquibase.change.AddColumnConfig;
import liquibase.change.Change;
import liquibase.change.ChangeMetaData;
import liquibase.change.ChangeParameterMetaData;
import liquibase.change.ColumnConfig;
import liquibase.change.ConstraintsConfig;
import liquibase.change.core.AbstractModifyDataChange;
import liquibase.change.core.AddAutoIncrementChange;
import liquibase.change.core.AddColumnChange;
import liquibase.change.core.AddDefaultValueChange;
import liquibase.change.core.AddForeignKeyConstraintChange;
import liquibase.change.core.AddLookupTableChange;
import liquibase.change.core.AddNotNullConstraintChange;
import liquibase.change.core.AddPrimaryKeyChange;
import liquibase.change.core.AddUniqueConstraintChange;
import liquibase.change.core.AlterSequenceChange;
import liquibase.change.core.CreateIndexChange;
import liquibase.change.core.CreateProcedureChange;
import liquibase.change.core.CreateSequenceChange;
import liquibase.change.core.CreateTableChange;
import liquibase.change.core.CreateViewChange;
import liquibase.change.core.DeleteDataChange;
import liquibase.change.core.DropColumnChange;
import liquibase.change.core.DropDefaultValueChange;
import liquibase.change.core.DropForeignKeyConstraintChange;
import liquibase.change.core.DropIndexChange;
import liquibase.change.core.DropNotNullConstraintChange;
import liquibase.change.core.DropPrimaryKeyChange;
import liquibase.change.core.DropProcedureChange;
import liquibase.change.core.DropSequenceChange;
import liquibase.change.core.DropTableChange;
import liquibase.change.core.DropUniqueConstraintChange;
import liquibase.change.core.DropViewChange;
import liquibase.change.core.EmptyChange;
import liquibase.change.core.ExecuteShellCommandChange;
import liquibase.change.core.InsertDataChange;
import liquibase.change.core.LoadDataChange;
import liquibase.change.core.LoadDataColumnConfig;
import liquibase.change.core.LoadUpdateDataChange;
import liquibase.change.core.MergeColumnChange;
import liquibase.change.core.ModifyDataTypeChange;
import liquibase.change.core.OutputChange;
import liquibase.change.core.RawSQLChange;
import liquibase.change.core.RenameColumnChange;
import liquibase.change.core.RenameSequenceChange;
import liquibase.change.core.RenameTableChange;
import liquibase.change.core.SQLFileChange;
import liquibase.change.core.SetColumnRemarksChange;
import liquibase.change.core.SetTableRemarksChange;
import liquibase.change.core.StopChange;
import liquibase.change.core.TagDatabaseChange;
import liquibase.change.core.UpdateDataChange;
import liquibase.parser.core.yaml.YamlChangeLogParser;
import liquibase.parser.core.yaml.YamlParser;
import liquibase.statement.DatabaseFunction;
import liquibase.statement.SequenceCurrentValueFunction;
import liquibase.statement.SequenceNextValueFunction;
import liquibase.structure.core.Column;
import liquibase.structure.core.ForeignKey;
import liquibase.structure.core.Index;
import liquibase.structure.core.PrimaryKey;
import liquibase.structure.core.Table;
import liquibase.structure.core.UniqueConstraint;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Runtime hints for Liquibase to support GraalVM native image execution.
 * Liquibase instantiates changesets and binds parameters dynamically via JavaBeans reflection.
 */
@Configuration
@ImportRuntimeHints(LiquibaseRuntimeHintsRegistrar.class)
public class LiquibaseRuntimeHintsRegistrar implements RuntimeHintsRegistrar {

    private static final MemberCategory[] ALL_MEMBER_CATEGORIES = MemberCategory.values();

    private static final List<Class<?>> LIQUIBASE_CLASSES = List.of(
            // Base change classes
            AbstractChange.class,
            AbstractSQLChange.class,
            AbstractTableChange.class,
            Change.class,
            AbstractModifyDataChange.class,

            // Core change implementations
            RawSQLChange.class,
            CreateTableChange.class,
            AddColumnChange.class,
            DropColumnChange.class,
            CreateIndexChange.class,
            DropIndexChange.class,
            AddForeignKeyConstraintChange.class,
            DropForeignKeyConstraintChange.class,
            AddUniqueConstraintChange.class,
            DropUniqueConstraintChange.class,
            AddPrimaryKeyChange.class,
            DropPrimaryKeyChange.class,
            RenameColumnChange.class,
            RenameTableChange.class,
            DropTableChange.class,
            ModifyDataTypeChange.class,
            SQLFileChange.class,
            ExecuteShellCommandChange.class,
            TagDatabaseChange.class,
            AddNotNullConstraintChange.class,
            DropNotNullConstraintChange.class,
            AddDefaultValueChange.class,
            DropDefaultValueChange.class,
            AddAutoIncrementChange.class,
            AddLookupTableChange.class,
            CreateViewChange.class,
            DropViewChange.class,
            CreateProcedureChange.class,
            DropProcedureChange.class,
            CreateSequenceChange.class,
            DropSequenceChange.class,
            AlterSequenceChange.class,
            RenameSequenceChange.class,
            SetTableRemarksChange.class,
            SetColumnRemarksChange.class,
            EmptyChange.class,
            OutputChange.class,
            StopChange.class,
            InsertDataChange.class,
            UpdateDataChange.class,
            DeleteDataChange.class,
            LoadDataChange.class,
            LoadUpdateDataChange.class,
            MergeColumnChange.class,

            // Config & metadata
            ColumnConfig.class,
            ConstraintsConfig.class,
            AddColumnConfig.class,
            LoadDataColumnConfig.class,
            ChangeMetaData.class,
            ChangeParameterMetaData.class,

            // Functions & SQL structures
            DatabaseFunction.class,
            SequenceNextValueFunction.class,
            SequenceCurrentValueFunction.class,
            Table.class,
            Column.class,
            Index.class,
            ForeignKey.class,
            PrimaryKey.class,
            UniqueConstraint.class,

            // Parsers
            YamlChangeLogParser.class,
            YamlParser.class);

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        for (Class<?> clazz : LIQUIBASE_CLASSES) {
            hints.reflection().registerType(clazz, ALL_MEMBER_CATEGORIES);
        }

        hints.resources().registerPattern("db/changelog-master.yaml");
        hints.resources().registerPattern("db/changelog/.*");
    }
}
