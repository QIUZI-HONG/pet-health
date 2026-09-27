/**
 * 宠物相关的枚举字典。
 *
 * 契约里这些字段是数字码（`species: 1犬2猫`、`gender: 0未知1公2母`，见 contract/app.yaml），
 * 数字码适合传，不适合显示。**显示用的标签收在这里一份**——之前四个页面各写了一遍
 * `species === 2 ? "猫" : "犬"`，改一处就会漏三处。
 *
 * 顺带说清这两套码不一样，别抄混：
 *   - `species`：1 犬 / 2 猫
 *   - `gender`（宠物的）：0 未知 / 1 公 / 2 母
 *   - `gender`（主人的）：0 未知 / 1 男 / 2 女
 */

export function speciesLabel(species: number | null | undefined): string {
  if (species === 1) return "犬";
  if (species === 2) return "猫";
  return "未知";
}

/** 宠物的性别：公 / 母。 */
export function genderLabel(gender: number | null | undefined): string {
  if (gender === 1) return "公";
  if (gender === 2) return "母";
  return "未知";
}

/** 主人的性别：男 / 女（同一个码，含义不同，所以单独一个函数）。 */
export function ownerGenderLabel(gender: number | null | undefined): string {
  if (gender === 1) return "男";
  if (gender === 2) return "女";
  return "未知";
}
