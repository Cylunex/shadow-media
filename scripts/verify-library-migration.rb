#!/usr/bin/env ruby
# Host-side SQLite smoke test, not a substitute for Android MigrationTestHelper/device tests.
require 'json'
require 'open3'

root = File.expand_path('..', __dir__)
schemas = File.join(root, 'core/database/schemas/top.cylunex.shadowmedia.database.ShadowMediaDatabase')
before = JSON.parse(File.read(File.join(schemas, '3.json'))).fetch('database')
after = JSON.parse(File.read(File.join(schemas, '4.json'))).fetch('database')
source = File.read(File.join(root, 'core/database/src/main/kotlin/top/cylunex/shadowmedia/database/ShadowMediaDatabase.kt'))
migration = source.split('val MIGRATION_3_4 =', 2).last.split('private val MIGRATION_1_2', 2).first.scan(/db\.execSQL\("([^"]+)"\)/).flatten
abort 'No migration statements found' if migration.empty?

def schema_sql(database)
  database.fetch('entities').flat_map do |entity|
    ([entity.fetch('createSql')] + entity.fetch('indices', []).map { |index| index.fetch('createSql') })
      .map { |sql| sql.gsub('${TABLE_NAME}', entity.fetch('tableName')) }
  end.join(";\n") + ";\n"
end

def query(sql, suffix)
  output, errors, status = Open3.capture3('sqlite3', '-json', ':memory:', stdin_data: sql + suffix)
  abort errors unless status.success?
  JSON.parse(output)
end

upgraded = schema_sql(before) + migration.join(";\n") + ";\n"
fresh = schema_sql(after)
columns = 'SELECT m.name AS tab, p.name, p.type, p."notnull", p.dflt_value, p.pk FROM sqlite_master m JOIN pragma_table_info(m.name) p WHERE m.type="table" ORDER BY m.name,p.cid;'
indices = 'SELECT m.name AS tab, i.name AS idx, i."unique", c.name AS col FROM sqlite_master m JOIN pragma_index_list(m.name) i JOIN pragma_index_info(i.name) c WHERE m.type="table" AND i.origin="c" ORDER BY m.name,i.name,c.seqno;'
abort 'Column mismatch after migration' unless query(upgraded, columns) == query(fresh, columns)
abort 'Index mismatch after migration' unless query(upgraded, indices) == query(fresh, indices)

# Every legacy table keeps its exact exported schema; the migration adds, never replaces.
before.fetch('entities').each do |entity|
  retained = after.fetch('entities').find { |candidate| candidate['tableName'] == entity['tableName'] }
  abort "Legacy schema changed: #{entity['tableName']}" unless retained == entity
end
puts 'PASS: v3 + MIGRATION_3_4 matches fresh v4 columns/indices; all legacy entities retained.'
