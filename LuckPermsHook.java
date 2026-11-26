package vn.treo;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.UserManager;

import java.util.UUID;

/** Chỉ được nạp khi server có LuckPerms. */
final class LuckPermsHook {

    private LuckPermsHook() {}

    /** Chép toàn bộ node (nhóm, prefix, quyền...) của người thật sang user của bot (chỉ trong bộ nhớ). */
    static void copyNodes(UUID realId, UUID botId) {
        LuckPerms lp = LuckPermsProvider.get();
        UserManager um = lp.getUserManager();
        um.loadUser(realId).thenAccept(real ->
                um.loadUser(botId).thenAccept(bot -> {
                    bot.data().clear();
                    for (var node : real.data().toCollection()) {
                        bot.data().add(node);
                    }
                }));
    }
}
