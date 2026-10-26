# 精妙背包 过滤槽越权转移 —— 问题代码与修复方案

核验版本：Sophisticated Backpacks **3.26.15.2227** / Sophisticated Core **1.5.18.2431**
（Minecraft 1.21.1 / NeoForge 21.1.265）

源码锚点（固定 commit，不是浮动分支）：

- Core：`732e1226bc80bf2a8a97be552d151350118cdc2b`
- Backpacks：`0207854768d47c1e51f82810f97d9b9829430ead`

---

## 0. 一句话结论

过滤槽（filter / ghost slot）正常是**只能写入、不能取出**的样板格。
`mayPickup()` 明确返回 `false`，但**整槽转移入口根本不调用它**：
客户端发一个 `int slotId`，服务端就把该槽的内容当普通物品合并进真实库存。

结果：样板 → 真实物品。已有物品被复制；装了服务端 JEI/EMI/REI 时可凭空造未持有物品。

---

## 1. 出问题的代码（按调用顺序）

### 1.1 客户端可控入口，没有任何源槽校验

[TransferFullSlotPayload.java L24-L34](https://github.com/P3pp3rF1y/SophisticatedCore/blob/732e1226bc80bf2a8a97be552d151350118cdc2b/src/main/java/net/p3pp3rf1y/sophisticatedcore/network/TransferFullSlotPayload.java#L24-L34)

```java
public static void handlePayload(TransferFullSlotPayload payload, IPayloadContext context) {
    Player player = context.player();
    if (!(player.containerMenu instanceof StorageContainerMenuBase<?> storageContainer)) {
        return;
    }
    Slot slot = storageContainer.getSlot(payload.slotId);
    ItemStack transferResult;
    do {
        transferResult = storageContainer.quickMoveStack(player, payload.slotId);
    } while (!transferResult.isEmpty() && ItemStack.isSameItem(slot.getItem(), transferResult));
}
```

问题点：

- `payload.slotId` **完全由客户端给出**，记录里就一个 `int`（L14）。
- 只校验了“当前打开的菜单类型”，没有校验槽位范围、槽位类型、是否可取。
- 没有 `mayPickup()`，没有 `instanceof IFilterSlot` 拒绝。
- `slotId = -1` 时 `getSlot(-1)` 会抛 `IndexOutOfBoundsException`（客户端可触发的异常路径）。

### 1.2 通用转移实现同样不校验源槽

[StorageContainerMenuBase.java L766-L801](https://github.com/P3pp3rF1y/SophisticatedCore/blob/732e1226bc80bf2a8a97be552d151350118cdc2b/src/main/java/net/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase.java#L766-L801)

```java
public ItemStack quickMoveStack(Player player, int index) {
    ItemStack itemstack = ItemStack.EMPTY;
    Slot slot = getSlot(index);              // ← 直接按编号取槽
    if (slot.hasItem()) {
        Optional<UpgradeContainerBase<?, ?>> upgradeContainer = getSlotUpgradeContainer(slot);
        ItemStack slotStack = upgradeContainer.map(c -> c.getSlotStackToTransfer(slot)).orElse(slot.getItem());
        ...
        if (!mergeSlotStack(slot, index, stackToMerge)) {
            return ItemStack.EMPTY;
        }
        ...
```

问题点：整个方法里**没有一次 `mayPickup()` 调用**（字节码实测引用数 = 0），也没有拒绝 `IFilterSlot`。

### 1.3 过滤槽走进“升级设置槽”分支，直送真实库存

[mergeSlotStack L805-L826](https://github.com/P3pp3rF1y/SophisticatedCore/blob/732e1226bc80bf2a8a97be552d151350118cdc2b/src/main/java/net/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase.java#L805-L826)

```java
} else if (isUpgradeSettingsSlot(index)) {
    if (getSlotUpgradeContainer(slot).map(c -> c.mergeIntoStorageFirst(slot)).orElse(true)) {
        return mergeStackToStorage(slot, slotStack) || ...;   // ← 先进真实库存
    }
    return ... || mergeStackToStorage(slot, slotStack);
}
```

过滤槽正是 `isUpgradeSettingsSlot`，于是走这条分支 → `mergeStackToStorage`。

[mergeStackToStorage L908-L915](https://github.com/P3pp3rF1y/SophisticatedCore/blob/732e1226bc80bf2a8a97be552d151350118cdc2b/src/main/java/net/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase.java#L908-L915)

```java
private boolean mergeStackToStorage(Slot slot, ItemStack slotStack) {
    ItemStack remaining = mergeItemStack(slotStack, 0, getNumberOfStorageInventorySlots(), false, false, true);
    if (remaining.getCount() != slotStack.getCount()) {
        slot.set(remaining);     // 样板槽被清空
        return true;
    }
    return false;
}
```

最终写入：[mergeItemStack L1673-L1681](https://github.com/P3pp3rF1y/SophisticatedCore/blob/732e1226bc80bf2a8a97be552d151350118cdc2b/src/main/java/net/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase.java#L1673-L1681)
→ 对真实 `Slot` 执行 `set()`，写的是背包真实库存，不是客户端画面。

“样板被清空”不等于“扣掉了真实物品”：清空的是幽灵样板，库存里多出来的那一份是真货。

### 1.4 存在但被绕过的防线

[FilterSlotItemHandler.java L14-L17](https://github.com/P3pp3rF1y/SophisticatedCore/blob/732e1226bc80bf2a8a97be552d151350118cdc2b/src/main/java/net/p3pp3rf1y/sophisticatedcore/common/gui/FilterSlotItemHandler.java#L14-L17)

```java
@Override
public boolean mayPickup(Player playerIn) {
    return false;
}
```

这道防线本身是对的，正常点击路径也确实拦住（实测点击收益 0）。
但 1.1 和 1.2 两条路径**从来不问它**，所以形同虚设。

### 1.5 未持有物品的额外前提：幽灵样板不校验“是否拥有”

[setGhostSlot L929-L951](https://github.com/P3pp3rF1y/SophisticatedCore/blob/732e1226bc80bf2a8a97be552d151350118cdc2b/src/main/java/net/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase.java#L929-L951)

```java
public boolean canSetGhostSlot(int slotNumber, ItemStack stack) {
    ...
    Slot slot = getSlot(slotNumber);
    return slot instanceof IFilterSlot && slot.isActive() && slot.mayPlace(stack)
            && getOpenContainer().map(container -> container.containsSlot(slot)).orElse(false);
}
```

校验了范围、过滤槽、活动状态、允许放入、所属升级已打开，**但没有校验玩家是否拥有该物品**——这对“幽灵过滤”是合理设计。
所以它本身不是漏洞，但和 1.1/1.2 组合后就成了“凭空造物”的源头。

这条消息的注册条件（服务端必须有配方查看器）：
[CommonPayloads.java L14](https://github.com/P3pp3rF1y/SophisticatedCore/blob/732e1226bc80bf2a8a97be552d151350118cdc2b/src/main/java/net/p3pp3rf1y/sophisticatedcore/compat/recipeviewers/common/CommonPayloads.java#L14)
`registrar.optional().playToServer(SetGhostSlotPayload.TYPE, ...)`

### 1.6 注册方向确认（不是纯客户端消息）

[ModPayloads.java L21](https://github.com/P3pp3rF1y/SophisticatedCore/blob/732e1226bc80bf2a8a97be552d151350118cdc2b/src/main/java/net/p3pp3rf1y/sophisticatedcore/init/ModPayloads.java#L21)

```java
registrar.playToServer(TransferFullSlotPayload.TYPE, TransferFullSlotPayload.STREAM_CODEC, TransferFullSlotPayload::handlePayload);
```

### 1.7 调用链

```
客户端 custom payload: sophisticatedcore:transfer_full_slot {slotId}
        ↓  (playToServer，无额外门禁)
TransferFullSlotPayload.handlePayload        ← 无范围/类型/可取校验
        ↓
StorageContainerMenuBase.quickMoveStack      ← 无 mayPickup，无 IFilterSlot 拒绝
        ↓
mergeSlotStack → isUpgradeSettingsSlot 分支   ← 过滤槽命中此分支
        ↓
mergeStackToStorage → mergeItemStack → Slot.set()   ← 写入真实库存
        ↓
样板槽被清空（假的“已扣除”），库存 +1（真的“多出来”）
```

---

## 2. 修复方案 A：上游两处补丁（推荐，改源码）

**一层不够**：只在 payload 入口拦，其它调用者仍可能走到 `quickMoveStack`；
只在 `quickMoveStack` 拦，`slotId = -1` 这类越界仍会在取槽时抛异常。两层都要加。

### 2.1 入口层

```java
public static void handlePayload(TransferFullSlotPayload payload, IPayloadContext context) {
    Player player = context.player();
    if (!(player.containerMenu instanceof StorageContainerMenuBase<?> storageContainer)) {
        return;
    }
    int slotId = payload.slotId();
    if (slotId < 0 || slotId >= storageContainer.getTotalSlotsNumber()) {
        return;                                     // 越界直接丢弃，别抛异常
    }
    Slot slot = storageContainer.getSlot(slotId);
    if (slot instanceof IFilterSlot                     // 过滤样板格
            || storageContainer.getSlotUpgradeContainer(slot).isPresent()  // 升级设置槽
            || !slot.isActive()                         // 未激活的槽
            || !slot.mayPickup(player)) {               // 统一走可取权限
        return;
    }
    ItemStack transferResult;
    do {
        transferResult = storageContainer.quickMoveStack(player, slotId);
    } while (!transferResult.isEmpty() && ItemStack.isSameItem(slot.getItem(), transferResult));
}
```

### 2.2 通用层

```java
public ItemStack quickMoveStack(Player player, int index) {
    if (index < 0 || index >= getTotalSlotsNumber()) {
        return ItemStack.EMPTY;
    }
    Slot slot = getSlot(index);
    if (slot instanceof IFilterSlot || getSlotUpgradeContainer(slot).isPresent()) {
        return ItemStack.EMPTY;      // 过滤样板/升级设置槽永远不是转移来源
    }
    ItemStack itemstack = ItemStack.EMPTY;
    if (slot.hasItem()) {
        ... 原逻辑不变 ...
    }
    return itemstack;
}
```

### 2.3 回归测试点

- 普通储物格 → 玩家背包：整槽转移正常
- 玩家背包 → 背包：正常
- 升级物品槽 → 背包：正常（`isUpgradeSlot` 分支不受影响）
- 合成结果槽 / 大堆叠：正常
- 过滤槽：点击取不出、整槽转移被拒
- 越界 `slotId`（-1、超大值）：静默丢弃，不抛异常

---

## 3. 修复方案 B：不改模组的服务端补丁（已实现并验证）

用 Mixin 拦同样的两个点，做成独立 jar，只装服务端。实际使用的代码：

[TransferFullSlotPayloadMixin](https://github.com/P3pp3rF1y/SophisticatedCore/blob/732e1226bc80bf2a8a97be552d151350118cdc2b/src/main/java/net/p3pp3rf1y/sophisticatedcore/network/TransferFullSlotPayload.java#L24)

```java
@Mixin(value = TransferFullSlotPayload.class, remap = false)
public class TransferFullSlotPayloadMixin {
    @Inject(method = "handlePayload", at = @At("HEAD"), cancellable = true)
    private static void bpfix$rejectFilterSlotTransfer(TransferFullSlotPayload payload, IPayloadContext context,
                                                       CallbackInfo ci) {
        Player player = context.player();
        if (!(player.containerMenu instanceof StorageContainerMenuBase<?> menu)) {
            return;
        }
        int slotId = payload.slotId();
        if (slotId < 0 || slotId >= menu.getTotalSlotsNumber()) {
            ci.cancel();
            return;
        }
        Slot slot = menu.getSlot(slotId);
        if (slot instanceof IFilterSlot || menu.getSlotUpgradeContainer(slot).isPresent() || !slot.isActive()) {
            ci.cancel();
        }
    }
}
```

```java
@Mixin(value = StorageContainerMenuBase.class, remap = false)
public abstract class StorageContainerMenuBaseMixin {
    @Inject(
            method = "quickMoveStack(Lnet/minecraft/world/entity/player/Player;I)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private void bpfix$refuseFilterSource(Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
        StorageContainerMenuBase<?> menu = (StorageContainerMenuBase<?>) (Object) this;
        if (index < 0 || index >= menu.getTotalSlotsNumber()) {
            cir.setReturnValue(ItemStack.EMPTY);
            return;
        }
        Slot slot = menu.getSlot(index);
        if (slot instanceof IFilterSlot || menu.getSlotUpgradeContainer(slot).isPresent()) {
            cir.setReturnValue(ItemStack.EMPTY);
        }
    }
}
```

Mixin 配置声明（NeoForge）：

```toml
[[mixins]]
config = "backpack_transfer_fix.mixins.json"
```

实测效果（3.26.15 / 1.5.18，NeoForge 21.1.265）：

| 项目 | 修复前 | 修复后 |
|---|---|---|
| 复制已有物品 | +1，重复 5 次 +5 | 0 / 0 |
| 凭空造未持有物品（服务端有 JEI） | +1 | 0 |
| 普通整槽转移 | 正常 | 正常（moved=3） |
| `slotId=-1` | 抛 IndexOutOfBounds | 静默丢弃 |

---

## 4. 修复方案 C：临时缓解（不能上补丁时）

| 手段 | 能挡住什么 | 局限 |
|---|---|---|
| 服务端拒绝 `transfer_full_slot` 消息 | 本轮的复制与造物入口 | 需要能拦包的工具；会同时废掉“整槽快捷转移”体验；不清理存量 |
| 移除服务端 JEI/EMI/REI | 挡住“凭空造未持有物品” | 复制已有物品仍然成立；有收益损失 |
| 禁用过滤升级/拾取升级 | 减少可达面 | 多种升级都有过滤槽；存量 wrapper 仍会加载 |
| 只封 `survival_infinity_upgrade` | 无 | 不修根因，其它物品照样能造 |

结论：临时手段只能压低风险，**根因仍需方案 A 或 B**。

---

## 5. 链接汇总

代码（固定 commit）

- [TransferFullSlotPayload.java（L24-L34 入口）](https://github.com/P3pp3rF1y/SophisticatedCore/blob/732e1226bc80bf2a8a97be552d151350118cdc2b/src/main/java/net/p3pp3rf1y/sophisticatedcore/network/TransferFullSlotPayload.java#L24-L34)
- [StorageContainerMenuBase.quickMoveStack（L766-L801）](https://github.com/P3pp3rF1y/SophisticatedCore/blob/732e1226bc80bf2a8a97be552d151350118cdc2b/src/main/java/net/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase.java#L766-L801)
- [StorageContainerMenuBase.mergeSlotStack（L805-L826）](https://github.com/P3pp3rF1y/SophisticatedCore/blob/732e1226bc80bf2a8a97be552d151350118cdc2b/src/main/java/net/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase.java#L805-L826)
- [StorageContainerMenuBase.mergeStackToStorage（L908-L915）](https://github.com/P3pp3rF1y/SophisticatedCore/blob/732e1226bc80bf2a8a97be552d151350118cdc2b/src/main/java/net/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase.java#L908-L915)
- [StorageContainerMenuBase.mergeItemStack（L1673-L1681）](https://github.com/P3pp3rF1y/SophisticatedCore/blob/732e1226bc80bf2a8a97be552d151350118cdc2b/src/main/java/net/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase.java#L1673-L1681)
- [StorageContainerMenuBase.setGhostSlot（L929-L951）](https://github.com/P3pp3rF1y/SophisticatedCore/blob/732e1226bc80bf2a8a97be552d151350118cdc2b/src/main/java/net/p3pp3rf1y/sophisticatedcore/common/gui/StorageContainerMenuBase.java#L929-L951)
- [FilterSlotItemHandler.mayPickup（L14-L17）](https://github.com/P3pp3rF1y/SophisticatedCore/blob/732e1226bc80bf2a8a97be552d151350118cdc2b/src/main/java/net/p3pp3rf1y/sophisticatedcore/common/gui/FilterSlotItemHandler.java#L14-L17)
- [ModPayloads 注册（L21）](https://github.com/P3pp3rF1y/SophisticatedCore/blob/732e1226bc80bf2a8a97be552d151350118cdc2b/src/main/java/net/p3pp3rf1y/sophisticatedcore/init/ModPayloads.java#L21)
- [CommonPayloads 幽灵槽注册（L14）](https://github.com/P3pp3rF1y/SophisticatedCore/blob/732e1226bc80bf2a8a97be552d151350118cdc2b/src/main/java/net/p3pp3rf1y/sophisticatedcore/compat/recipeviewers/common/CommonPayloads.java#L14)

发行物

- [Backpacks 3.26.15.2227](https://modrinth.com/mod/sophisticated-backpacks/version/3SXYeyvp) · SHA256 `0c0c8728…fab85`
- [Core 1.5.18.2431](https://modrinth.com/mod/sophisticated-core/version/DqcfpFN9) · SHA256 `99ac05bd…53620d`

---

## 6. 备注

- 行号取自上面两个固定 commit 的 `src/main/java`，与发行 JAR 的字节码一致（已用 javap 交叉核对：`handlePayload` 偏移 47 调 `quickMoveStack`、`quickMoveStack` 偏移 141 调 `mergeSlotStack`、`mayPickup` 引用数 0）。
- 本文只描述缺陷与修复，不含可直接用于攻击的客户端程序。
- 修复不清理已生成的物品；存量清查需另行处理（`data/sophisticatedbackpacks.dat` + 玩家/容器数据）。
