package kr.finsight.admin;

import java.util.List;
import java.util.Map;
import kr.finsight.news.NewsEditorial;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("local")
class NewsPublicationJob {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(NewsPublicationJob.class);
    private final NewsEditorial news;
    private final AdminMutationJournal journal;
    private final TransactionTemplate transactions;

    NewsPublicationJob(NewsEditorial news, AdminMutationJournal journal, TransactionTemplate transactions) {
        this.news = news;
        this.journal = journal;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 10000)
    void processDue() {
        for (var due : news.duePublications()) {
            try {
                transactions.executeWithoutResult(status -> {
                    var activation = news.processPublication(due);
                    if (activation == null) return;
                    var actor = new AdminAuth.Identity(new AdminAuth.Admin(activation.actorId(), ""), List.of("SUPER_ADMIN"));
                    var article = activation.article();
                    journal.append(actor, "NEWS_SCHEDULE_ACTIVATED", "NEWS_ARTICLE", article.id().toString(),
                            Map.of("revision", article.revision(), "version", article.version(), "source", "SCHEDULER"));
                });
            } catch (RuntimeException failure) {
                LOG.warn("뉴스 예약 처리 실패 {} 버전 {} ({})", due.articleId(), due.revision(), failure.getClass().getSimpleName());
            }
        }
    }
}
