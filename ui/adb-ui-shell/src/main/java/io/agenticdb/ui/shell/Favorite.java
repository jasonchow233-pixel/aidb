package io.agenticdb.ui.shell;

/**
 * 收藏夹里的一条（⭐ R51 T1）。
 *
 * <p>为什么要带**所属连接**：表名不唯一。同一个 MySQL 服务上
 * {@code orders.users} 与 {@code audit.users} 是两张表，而收藏原先只按名字记 ——
 * 收藏一张，另一张也跟着亮星、一起进「只看收藏」的过滤，从收藏夹点开还永远
 * 开到**当前连接**上（打开的可能是另一张同名表）。
 *
 * @param connectionId 收藏的那个对象所在的连接（catalog 虚拟连接也算，
 *                     {@code 父id@库名} 自己就带库名）；旧版只存了名字的收藏留空，
 *                     含义是「按当前连接打开」，不猜它属于哪个库
 * @param table        对象名
 * @param label        给人看的文字（界面层按语言拼好，模型/存储不参与）
 */
record Favorite(String connectionId, String table, String label) {
}
