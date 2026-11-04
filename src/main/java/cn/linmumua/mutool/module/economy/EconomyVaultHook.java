package cn.linmumua.mutool.module.economy;

import cn.linmumua.mutool.Mutool;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import taboolib.common.platform.function.IOKt;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Optional Vault bridge for the economy module.
 *
 * <p>When the Vault plugin is installed, this registers a Vault {@code Economy} service
 * provider so other plugins (shops, kits, …) can read and mutate the same balances that
 * {@code /money}, {@code /pay} and {@code /moneyadmin} operate on.</p>
 *
 * <p><b>Why reflection?</b> Mutool declares Vault as a soft {@code join-classpath}
 * dependency in {@code paper-plugin.yml} but does <em>not</em> depend on the Vault API at
 * compile time (and this module may not edit {@code build.gradle.kts} to add it). We
 * therefore obtain the {@code net.milkbowl.vault.economy.Economy} interface from the Vault
 * plugin's class loader at runtime and satisfy it with a {@link Proxy}. The whole class is
 * inert when Vault is absent, and every failure is caught and logged — a missing or broken
 * Vault never affects the base economy commands.</p>
 */
final class EconomyVaultHook {

    private final EconomyModule module;
    private Object provider;
    private Class<?> economyClass;

    EconomyVaultHook(EconomyModule module) {
        this.module = module;
    }

    /** Try to register the Vault service. Returns {@code true} on success. */
    boolean register(Mutool plugin) {
        Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
        if (vault == null) {
            return false;
        }
        try {
            ClassLoader loader = vault.getClass().getClassLoader();
            this.economyClass = Class.forName("net.milkbowl.vault.economy.Economy", true, loader);
            Class<?> responseClass = Class.forName("net.milkbowl.vault.economy.EconomyResponse", true, loader);
            Class<?> responseTypeClass =
                    Class.forName("net.milkbowl.vault.economy.EconomyResponse$ResponseType", true, loader);

            Constructor<?> responseCtor = responseClass.getConstructor(
                    double.class, double.class, responseTypeClass, String.class);
            Object successType = enumConstant(responseTypeClass, "SUCCESS");
            Object failureType = enumConstant(responseTypeClass, "FAILURE");

            Responses responses = new Responses(responseClass, responseCtor, successType, failureType);

            this.provider = Proxy.newProxyInstance(
                    this.economyClass.getClassLoader(),
                    new Class<?>[]{this.economyClass},
                    new Handler(this.module, responses));

            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<Object> rawClass = (Class) this.economyClass;
            Bukkit.getServicesManager().register(rawClass, this.provider, plugin, ServicePriority.Normal);

            IOKt.info("[Mutool/Economy] 已向 Vault 注册 Economy 服务提供者");
            return true;
        } catch (Throwable throwable) {
            this.module.logWarn("Vault 经济服务注册失败（已跳过）: " + throwable);
            this.provider = null;
            this.economyClass = null;
            return false;
        }
    }

    /** Unregister the Vault service if it was registered. */
    void unregister() {
        if (this.economyClass == null || this.provider == null) {
            return;
        }
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<Object> rawClass = (Class) this.economyClass;
            Bukkit.getServicesManager().unregister(rawClass, this.provider);
        } catch (Throwable ignored) {
        }
        this.provider = null;
        this.economyClass = null;
    }

    private static Object enumConstant(Class<?> type, String name) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object value = Enum.valueOf((Class) type, name);
        return value;
    }

    /** Bundles the reflective handles needed to build Vault {@code EconomyResponse} values. */
    private static final class Responses {
        private final Class<?> responseClass;
        private final Constructor<?> constructor;
        private final Object successType;
        private final Object failureType;

        private Responses(Class<?> responseClass, Constructor<?> constructor,
                          Object successType, Object failureType) {
            this.responseClass = responseClass;
            this.constructor = constructor;
            this.successType = successType;
            this.failureType = failureType;
        }

        Object of(boolean success, double amount, double balance, String error) {
            try {
                return this.constructor.newInstance(amount, balance, success ? this.successType : this.failureType, error);
            } catch (Throwable throwable) {
                return null;
            }
        }

        Class<?> type() {
            return this.responseClass;
        }
    }

    /** The {@link InvocationHandler} that implements the Vault {@code Economy} interface. */
    private static final class Handler implements InvocationHandler {

        private final EconomyModule module;
        private final Responses responses;

        private Handler(EconomyModule module, Responses responses) {
            this.module = module;
            this.responses = responses;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // Object plumbing so the proxy behaves like a normal object.
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "MutoolVaultEconomy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args == null ? null : args[0]);
                    default -> null;
                };
            }

            Object[] arguments = args == null ? new Object[0] : args;

            switch (method.getName()) {
                case "isEnabled":
                    return this.module.isEnabled();
                case "getName":
                    return "Mutool";
                case "hasBankSupport":
                    return false;
                case "fractionalDigits":
                    return 2;
                case "format":
                    return String.format("%.2f", lastDouble(arguments));
                case "currencyNamePlural":
                case "currencyNameSingular":
                    return this.module.currencyName();
                case "hasAccount":
                    return player(arguments) != null && this.module.hasAccount(player(arguments));
                case "getBalance": {
                    UUID id = player(arguments);
                    return id == null ? 0.0D : this.module.balanceOf(id).doubleValue();
                }
                case "has": {
                    UUID id = player(arguments);
                    if (id == null) {
                        return false;
                    }
                    BigDecimal money = money(lastDouble(arguments));
                    BigDecimal balance = this.module.balanceOf(id);
                    return money != null && balance.compareTo(money) >= 0;
                }
                case "withdrawPlayer":
                    return this.withdraw(arguments);
                case "depositPlayer":
                    return this.deposit(arguments);
                case "createPlayerAccount": {
                    UUID id = player(arguments);
                    return id != null && this.module.createAccount(id);
                }
                case "getBanks":
                    return Collections.emptyList();
                default:
                    break;
            }

            // Any remaining method (bank operations, etc.). Respond with a failure response
            // when Vault expects one, otherwise a type-appropriate default.
            if (method.getReturnType() == this.responses.type()) {
                return this.responses.of(false, 0.0D, 0.0D, null);
            }
            return defaultValue(method.getReturnType());
        }

        private Object withdraw(Object[] args) {
            UUID id = player(args);
            double amount = lastDouble(args);
            if (id == null) {
                return this.responses.of(false, amount, 0.0D, "Player not found");
            }
            BigDecimal money = money(amount);
            BigDecimal balance = this.module.balanceOf(id);
            if (money == null) {
                return this.responses.of(false, amount, balance.doubleValue(), "Amount cannot be negative");
            }
            if (balance.compareTo(money) < 0) {
                return this.responses.of(false, amount, balance.doubleValue(), "Not enough money");
            }
            this.module.takeBalance(id, money, true);
            return this.responses.of(true, amount, this.module.balanceOf(id).doubleValue(), null);
        }

        private Object deposit(Object[] args) {
            UUID id = player(args);
            double amount = lastDouble(args);
            if (id == null) {
                return this.responses.of(false, amount, 0.0D, "Player not found");
            }
            BigDecimal money = money(amount);
            if (money == null) {
                return this.responses.of(false, amount, this.module.balanceOf(id).doubleValue(),
                        "Amount cannot be negative");
            }
            this.module.addBalance(id, money);
            return this.responses.of(true, amount, this.module.balanceOf(id).doubleValue(), null);
        }

        /** Extract the player UUID from the first argument (OfflinePlayer or player name). */
        private static UUID player(Object[] args) {
            if (args.length == 0 || args[0] == null) {
                return null;
            }
            Object first = args[0];
            if (first instanceof OfflinePlayer offline) {
                return offline.getUniqueId();
            }
            if (first instanceof String name) {
                if (name.isBlank()) {
                    return null;
                }
                var online = Bukkit.getPlayerExact(name);
                if (online != null) {
                    return online.getUniqueId();
                }
                try {
                    OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
                    return offline == null ? null : offline.getUniqueId();
                } catch (Throwable throwable) {
                    return null;
                }
            }
            return null;
        }

        private static double lastDouble(Object[] args) {
            for (int index = args.length - 1; index >= 0; index--) {
                if (args[index] instanceof Number number) {
                    return number.doubleValue();
                }
            }
            return 0.0D;
        }

        private static BigDecimal money(double amount) {
            if (amount < 0 || Double.isNaN(amount) || Double.isInfinite(amount)) {
                return null;
            }
            return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP);
        }

        private static Object defaultValue(Class<?> type) {
            if (type == boolean.class) {
                return false;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == double.class) {
                return 0.0D;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == float.class) {
                return 0.0F;
            }
            if (type == short.class) {
                return (short) 0;
            }
            if (type == byte.class) {
                return (byte) 0;
            }
            if (type == char.class) {
                return (char) 0;
            }
            if (type == java.util.List.class) {
                return Collections.emptyList();
            }
            return null;
        }
    }
}
