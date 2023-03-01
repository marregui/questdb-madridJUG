/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2023 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb;

import io.questdb.cairo.sql.OperationFuture;
import io.questdb.cairo.sql.Record;
import io.questdb.cairo.sql.RecordCursor;
import io.questdb.cairo.sql.RecordCursorFactory;
import io.questdb.griffin.CompiledQuery;
import io.questdb.griffin.SqlCompiler;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.mp.SOCountDownLatch;

import static io.questdb.Utils.*;

public class SameProcessThroughCompiler {
    private static final Log LOG = LogFactory.getLog(SameProcessThroughCompiler.class);

    public static void main(String[] args) throws Exception {

        String rootDir = "QuestDB_Root";
        String tableName = "trades_again";

        configureQuestDB(rootDir);
        try (
                ServerMain qdb = new ServerMain("-d", rootDir, Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION);
                SqlCompiler compiler = new SqlCompiler(qdb.getCairoEngine());
                SqlExecutionContext context = createSqlExecutionContext(qdb.getCairoEngine())
        ) {
            qdb.start();

            // we would like to know when the table writer is released, which means
            // the data has been inserted and is available for reading.
            SOCountDownLatch dataReady = registerInterestInWriterCompleted(qdb.getCairoEngine(), tableName, LOG);

            try {
                // create table - insert random data
                CompiledQuery compiledCreate = compiler.compile(String.format("""
                                CREATE TABLE %s AS (
                                    SELECT
                                        rnd_symbol('EURO', 'USD', 'OTHER') symbol,
                                        rnd_double() * 50.0 price,
                                        rnd_double() * 20.0 amount,
                                        to_timestamp('2022-12-30', 'yyyy-MM-dd') + x * 60 * 100000 timestamp
                                    FROM long_sequence(4000000)
                                ), INDEX(symbol capacity 128) TIMESTAMP(timestamp) PARTITION BY MONTH;
                                """, tableName),
                        context);
                try (OperationFuture op = compiledCreate.execute(null)) {
                    op.await();
                }
                dataReady.await();
                LOG.info().$("Data ready").$();
            } catch (SqlException ex) {
                LOG.info().$("Data already exists").$();
                dataReady.countDown();
            }

            // run a select query
            long start = System.currentTimeMillis();
            CompiledQuery compiledSelect = compiler.compile(String.format("""
                            SELECT * FROM %s
                            WHERE symbol='EURO' AND price > 49.99 AND amount > 15.0 AND timestamp BETWEEN '2022-12' AND '2023-02';
                            """, tableName),
                    context);
            try (
                    RecordCursorFactory factory = compiledSelect.getRecordCursorFactory();
                    RecordCursor cursor = factory.getCursor(context)
            ) {
                Record record = cursor.getRecord();
                while (cursor.hasNext()) {
                    CharSequence symbol = record.getSym(0);
                    double price = record.getDouble(1);
                    double amount = record.getDouble(2);
                    long timestamp = record.getTimestamp(3);
                    LOG.info().$(symbol).$(" [price=").$(price)
                            .$(", amount=").$(amount)
                            .$(", timestamp=").$ts(timestamp)
                            .I$();
                }
                LOG.info().$("Took: ").$(System.currentTimeMillis() - start).$(" ms").$();
            }
        }
    }
}
