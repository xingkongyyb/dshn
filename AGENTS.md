# 给 AI 助手的仓库规则（DSHN）

这个仓库由多个 AI（Claude、Codex/GPT、Gemini）轮流修改，仓库主人是“天天”。
**每次提交前请先读完本文件。**

## 提交规则

1. **确认分支**：提交前先运行 `git branch --show-current`。
   模块化之后的改动都提交到 `aistudio-refactor`。分支不对就先停下来问天天，不要自己新建或切换分支。
2. **用自己的身份署名**：
   - 名字：填你此刻实际的模型名称和版本。自己确认，不要照抄别人的名字或下面的例子。
   - 邮箱：按你所属的公司填固定邮箱：

     | 谁 | 固定邮箱 |
     |---|---|
     | Anthropic 的 Claude | `claude@xiaomeng.local` |
     | OpenAI 的 Codex / GPT | `codex@xiaomeng.local` |
     | Google 的 Gemini | `gemini@xiaomeng.local` |
     | 天天（人工提交） | `tiantian@xiaomeng.local` |

   - 命令格式：

     ```
     git -c user.name="你的模型名" -c user.email="你的固定邮箱" commit -m "改了什么"
     ```
3. **不要改 git 配置里的名字和邮箱**，全局和仓库级都不要动。仓库默认名字是“未署名”，这是故意设的。
4. **只在本地提交，不要推送**到 GitHub 或任何远程仓库，除非天天明确要求。
5. **被拒绝时**：如果提交时提示“提交被拒绝：未署名”，说明没有按第 2 条署名，按格式重新提交即可。不要用 `--no-verify` 跳过检查。
6. **写清楚改了什么**：提交说明第一行写改动摘要，正文写原因和影响范围。不要把 API Key、密码等任何密钥写进仓库文件或提交说明。

## 说明

- 本机装有提交前检查（`.git/hooks/pre-commit` 和 `pre-merge-commit`），只接受上表中的 4 个邮箱，名字不能为空，也不能是“未署名”。
- 检查只在本机生效，AI Studio 或 GitHub 网页上的改动不经过它。
- 分支用途与同步注意事项见 `docs/architecture/modularization.md` 第 8 节。
