package com.example.orders.config;

import com.example.orders.entity.Role;
import com.example.orders.entity.User;
import com.example.orders.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the ADMIN and SUPPORT accounts that cannot be self-registered.
 *
 * <p>Only these two roles. A CUSTOMER can register through the API, so seeding one would just be extra
 * state nobody needs.
 *
 * <h2>Three independent guards, because this creates privileged accounts</h2>
 *
 * <ol>
 *   <li><b>Off by default.</b> {@code @ConditionalOnProperty} without {@code matchIfMissing}, so the bean
 *       does not exist unless {@code APP_SEED_ENABLED=true} is set deliberately. Doing nothing gives you
 *       the safe state.
 *   <li><b>Refuses to exist under the {@code prod} profile.</b> The constructor throws, so the context
 *       never refreshes and no port is ever opened - rather than skipping quietly, which would leave
 *       somebody believing there is an admin account when there is not.
 *   <li><b>No default password anywhere.</b> {@link SeedProperties} validation rejects a blank one, so
 *       enabling seeding without supplying a password is a startup failure, not a well-known credential.
 * </ol>
 *
 * <p>Idempotent: an account whose email already exists is left completely alone. It is deliberately not
 * a "reset to these values" operation - that would silently overwrite a password somebody changed, and
 * would re-grant ADMIN to an account that had been demoted on purpose.
 */
@Component
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SeedProperties.class)
class StaffAccountSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StaffAccountSeeder.class);

    private final SeedProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    StaffAccountSeeder(SeedProperties properties, UserRepository userRepository,
                       PasswordEncoder passwordEncoder, Environment environment) {
        // Checked in the constructor, not in run(). ApplicationRunners execute after the context is
        // ready and after the web server has started accepting connections, so a check there means the
        // application briefly serves traffic before dying. Failing during bean creation means the
        // context never refreshes, no port is ever opened, and a container simply crash-loops - which
        // is what a misconfigured deployment should do.
        //
        // Loud, not quiet: an enabled seeder in production must be impossible to deploy, and a warning
        // in a log nobody reads is not that.
        if (environment.matchesProfiles("prod")) {
            throw new IllegalStateException("""
                    app.seed.enabled is true while the 'prod' profile is active. Seeded accounts take \
                    their credentials from the environment and are a development convenience only - \
                    creating a privileged account this way in production is not supported. Unset \
                    APP_SEED_ENABLED.""");
        }
        this.properties = properties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed(properties.admin(), Role.ADMIN);
        seed(properties.support(), Role.SUPPORT);
    }

    private void seed(SeedProperties.Account account, Role role) {
        if (account == null) {
            log.info("No {} account configured; skipping", role);
            return;
        }
        // findByEmailIgnoreCase, not findByEmail: uq_users_email_lower treats addresses
        // case-insensitively, so a case-sensitive check would let this attempt an insert the database
        // then rejects.
        if (userRepository.existsByEmailIgnoreCase(account.email())) {
            log.info("{} account {} already exists; leaving it untouched", role, account.email());
            return;
        }
        if (userRepository.existsByUsername(account.username())) {
            log.warn("Username '{}' is taken by another account; not seeding {}",
                    account.username(), role);
            return;
        }

        User user = userRepository.save(new User(account.username(), account.email(),
                passwordEncoder.encode(account.password()), role));

        // The email is logged, the password never is - it would otherwise sit in the log of every
        // developer machine and CI run.
        log.warn("Seeded {} account id={} email={} - development convenience, not for any deployed "
                + "environment", role, user.getId(), user.getEmail());
    }
}
