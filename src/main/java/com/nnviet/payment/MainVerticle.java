package com.nnviet.payment;

import com.nnviet.payment.common.db.Tx;
import com.nnviet.payment.common.errors.ErrorMapper;
import com.nnviet.payment.common.idempotency.IdempotencyCache;
import com.nnviet.payment.common.idempotency.IdempotencyRepository;
import com.nnviet.payment.common.idempotency.IdempotencyService;
import com.nnviet.payment.common.web.Web;
import com.nnviet.payment.config.AppConfig;
import com.nnviet.payment.config.DataSources;
import com.nnviet.payment.ledger.HoldRepository;
import com.nnviet.payment.ledger.LedgerHandler;
import com.nnviet.payment.ledger.LedgerRepository;
import com.nnviet.payment.ledger.task.AcquireHoldTask;
import com.nnviet.payment.ledger.task.AppendLedgerEntryTask;
import com.nnviet.payment.ledger.task.CreditTask;
import com.nnviet.payment.ledger.task.DebitTask;
import com.nnviet.payment.ledger.task.SettleHoldTask;
import com.nnviet.payment.ledger.task.ValidateBalanceTask;
import com.nnviet.payment.ledger.workflow.CommitHoldWorkflow;
import com.nnviet.payment.ledger.workflow.DepositWorkflow;
import com.nnviet.payment.ledger.workflow.ExpireHoldsWorkflow;
import com.nnviet.payment.ledger.workflow.HoldWorkflow;
import com.nnviet.payment.ledger.workflow.RollbackHoldWorkflow;
import com.nnviet.payment.ledger.workflow.TransferWorkflow;
import com.nnviet.payment.ledger.workflow.WithdrawWorkflow;
import com.nnviet.payment.outbox.EventPublisher;
import com.nnviet.payment.outbox.FileEventPublisher;
import com.nnviet.payment.outbox.LoggingEventPublisher;
import com.nnviet.payment.outbox.OutboxRelay;
import com.nnviet.payment.outbox.OutboxRepository;
import com.nnviet.payment.outbox.OutboxWriter;
import com.nnviet.payment.wallet.WalletHandler;
import com.nnviet.payment.wallet.WalletRepository;
import com.nnviet.payment.wallet.WalletService;
import com.zaxxer.hikari.HikariDataSource;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.vertx.core.AbstractVerticle;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.WorkerExecutor;
import io.vertx.mutiny.ext.web.Router;
import io.vertx.mutiny.ext.web.RoutingContext;
import io.vertx.mutiny.ext.web.handler.BodyHandler;
import io.vertx.mutiny.redis.client.RedisAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * Wires the whole service: pools, repositories, tasks, workflows, HTTP router,
 * outbox relay and hold-expiry sweeper. Construction only - every mechanism
 * lives in its own class.
 */
public class MainVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(MainVerticle.class);

    private HikariDataSource dataSource;

    @Override
    public Uni<Void> asyncStart() {
        AppConfig config = AppConfig.fromEnv();
        // pool creation + schema.sql are blocking JDBC: off the event loop
        return vertx.executeBlocking(() -> {
            HikariDataSource ds = DataSources.createPool(config);
            DataSources.applySchema(ds);
            return ds;
        }, false).chain(ds -> {
            this.dataSource = ds;
            return startService(config, ds);
        });
    }

    private Uni<Void> startService(AppConfig config, HikariDataSource ds) {
        // worker pool sized to the connection pool: a db task never queues on a connection
        WorkerExecutor dbWorkers =
                vertx.createSharedWorkerExecutor("db-worker", config.dbPoolSize());
        Tx tx = new Tx(ds, dbWorkers);

        WalletRepository walletRepo = new WalletRepository();
        LedgerRepository ledgerRepo = new LedgerRepository();
        HoldRepository holdRepo = new HoldRepository();
        OutboxRepository outboxRepo = new OutboxRepository();
        IdempotencyRepository idemRepo = new IdempotencyRepository();
        OutboxWriter outboxWriter = new OutboxWriter();

        ValidateBalanceTask validateBalance = new ValidateBalanceTask();
        DebitTask debit = new DebitTask(walletRepo);
        CreditTask credit = new CreditTask(walletRepo);
        AcquireHoldTask acquireHold = new AcquireHoldTask(walletRepo, holdRepo);
        SettleHoldTask settleHold = new SettleHoldTask(walletRepo, holdRepo);
        AppendLedgerEntryTask appendEntry = new AppendLedgerEntryTask(ledgerRepo);

        DepositWorkflow deposit =
                new DepositWorkflow(walletRepo, credit, appendEntry, outboxWriter);
        WithdrawWorkflow withdraw =
                new WithdrawWorkflow(walletRepo, validateBalance, debit, appendEntry, outboxWriter);
        TransferWorkflow transfer = new TransferWorkflow(
                walletRepo, validateBalance, debit, credit, appendEntry, outboxWriter);
        HoldWorkflow hold =
                new HoldWorkflow(walletRepo, validateBalance, acquireHold, outboxWriter);
        CommitHoldWorkflow commitHold = new CommitHoldWorkflow(
                walletRepo, holdRepo, settleHold, debit, credit, appendEntry, outboxWriter);
        RollbackHoldWorkflow rollbackHold =
                new RollbackHoldWorkflow(walletRepo, holdRepo, settleHold, outboxWriter);
        ExpireHoldsWorkflow expireHolds = new ExpireHoldsWorkflow(
                tx, holdRepo, walletRepo, settleHold, outboxWriter,
                config.sweepIntervalMs(), config.sweepBatchSize());

        RedisAPI redis = DataSources.createRedis(vertx, config);
        IdempotencyCache cache = new IdempotencyCache(redis, config.idemCacheTtlSeconds());
        IdempotencyService idempotency = new IdempotencyService(tx, idemRepo, cache);

        EventPublisher publisher = "file".equalsIgnoreCase(config.publisher())
                ? new FileEventPublisher(config.eventSinkPath())
                : new LoggingEventPublisher();
        OutboxRelay relay = new OutboxRelay(
                tx, outboxRepo, publisher, config.relayIntervalMs(), config.relayBatchSize());

        WalletHandler walletHandler = new WalletHandler(new WalletService(tx, walletRepo));
        LedgerHandler ledgerHandler = new LedgerHandler(tx, idempotency, ledgerRepo,
                deposit, withdraw, transfer, hold, commitHold, rollbackHold);

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.route().failureHandler(ErrorMapper::handle);
        walletHandler.register(router);
        ledgerHandler.register(router);
        router.get("/health").handler(ctx -> health(ctx, tx, redis));

        return vertx.createHttpServer()
                .requestHandler(router::handle)
                .listen(config.httpPort())
                .invoke(server -> {
                    relay.start(vertx);
                    expireHolds.start(vertx);
                    log.info("payment service listening on port {}", config.httpPort());
                })
                .replaceWithVoid();
    }

    /** DB down is fatal (503); Redis down is reported but non-fatal - it is only a cache. */
    private void health(RoutingContext ctx, Tx tx, RedisAPI redis) {
        tx.inTx(conn -> {
            try (var stmt = conn.createStatement()) {
                stmt.execute("SELECT 1");
            }
            return true;
        }).chain(dbOk -> redis.ping(List.of())
                .ifNoItem().after(Duration.ofMillis(250)).fail()
                .map(response -> "UP")
                .onFailure().recoverWithItem("DOWN")
        ).subscribe().with(
                redisStatus -> Web.respondJson(ctx, 200, new JsonObject()
                        .put("status", "UP").put("db", "UP").put("redis", redisStatus)),
                failure -> Web.respondJson(ctx, 503, new JsonObject()
                        .put("status", "DOWN").put("db", "DOWN")));
    }

    @Override
    public Uni<Void> asyncStop() {
        if (dataSource != null) {
            dataSource.close();
        }
        return Uni.createFrom().voidItem();
    }
}
