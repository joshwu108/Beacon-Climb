"""Model A neural candidate (BC-0204): a Deep Sets encoder over typed hold tokens.

Each hold becomes a token: learned embedding of the physical hold (vocabulary fitted on train;
index 0 = unseen), normalised x/y and a one-hot role. ``phi`` maps tokens independently;
sum, mean and max pooling make the encoder order-free; ``rho`` adds the angle and produces a
32-dimensional route embedding and the predicted grade. Loss is Huber on the grade.

Selection (epoch count via early stopping, and the variant) uses val only; the test split is
opened once by ``run_deepsets.py --stage test`` after the candidate is frozen.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import torch
from torch import nn

from beacon_ml.features import model_a as fa

ROLE_INDEX = {role: i for i, role in enumerate(fa.ROLES)}
TOKEN_NUMERIC = 2 + len(fa.ROLES)  # x, y, role one-hot
ANGLE_SCALE = 70.0


@dataclass(frozen=True)
class Encoded:
    hold_ids: torch.Tensor  # long [n, L], 0 = padding/unseen
    numeric: torch.Tensor  # float [n, L, TOKEN_NUMERIC]
    mask: torch.Tensor  # float [n, L], 1 for real holds
    angle: torch.Tensor  # float [n, 1], angle / 70
    grade: torch.Tensor  # float [n]


class TokenVocab:
    def __init__(self, hold_ids):
        self.index = {h: i + 1 for i, h in enumerate(sorted(set(hold_ids)))}

    @classmethod
    def fit(cls, rows):
        return cls(h["id"] for r in rows for h in r["holds"])

    @property
    def size(self) -> int:
        return len(self.index) + 1


def encode(rows: list[dict], vocab: TokenVocab, max_holds: int) -> Encoded:
    n = len(rows)
    ids = np.zeros((n, max_holds), dtype=np.int64)
    numeric = np.zeros((n, max_holds, TOKEN_NUMERIC), dtype=np.float32)
    mask = np.zeros((n, max_holds), dtype=np.float32)
    for i, row in enumerate(rows):
        for j, hold in enumerate(row["holds"][:max_holds]):
            ids[i, j] = vocab.index.get(hold["id"], 0)
            x, y = fa.normalized_xy(hold["x"], hold["y"])
            numeric[i, j, 0], numeric[i, j, 1] = x, y
            numeric[i, j, 2 + ROLE_INDEX[hold["role"]]] = 1.0
            mask[i, j] = 1.0
    return Encoded(
        hold_ids=torch.from_numpy(ids),
        numeric=torch.from_numpy(numeric),
        mask=torch.from_numpy(mask),
        angle=torch.tensor([[r["angle_deg"] / ANGLE_SCALE] for r in rows], dtype=torch.float32),
        grade=torch.tensor([r["grade"] for r in rows], dtype=torch.float32),
    )


class DeepSets(nn.Module):
    def __init__(
        self,
        vocab_size: int,
        hold_dim: int = 32,
        hidden: int = 128,
        embedding_dim: int = 32,
        grade_mean: float = 0.0,
    ):
        super().__init__()
        self.hold_embedding = nn.Embedding(vocab_size, hold_dim, padding_idx=0)
        self.phi = nn.Sequential(
            nn.Linear(hold_dim + TOKEN_NUMERIC, hidden),
            nn.GELU(),
            nn.Linear(hidden, hidden),
            nn.GELU(),
        )
        self.rho = nn.Sequential(
            nn.Linear(3 * hidden + 1, hidden),
            nn.GELU(),
            nn.Linear(hidden, embedding_dim),
            nn.GELU(),
        )
        self.head = nn.Linear(embedding_dim, 1)
        nn.init.constant_(self.head.bias, grade_mean)

    def embed(self, hold_ids, numeric, mask, angle):
        tokens = torch.cat([self.hold_embedding(hold_ids), numeric], dim=-1)
        h = self.phi(tokens) * mask.unsqueeze(-1)
        count = mask.sum(dim=1, keepdim=True).clamp(min=1.0)
        pooled_sum = h.sum(dim=1)
        pooled_mean = pooled_sum / count
        pooled_max = h.masked_fill(mask.unsqueeze(-1) == 0, -1e4).max(dim=1).values
        return self.rho(torch.cat([pooled_sum / 10.0, pooled_mean, pooled_max, angle], dim=-1))

    def forward(self, hold_ids, numeric, mask, angle):
        return self.head(self.embed(hold_ids, numeric, mask, angle)).squeeze(-1)


def predict(model: DeepSets, data: Encoded, batch: int = 4096) -> np.ndarray:
    model.eval()
    out = []
    with torch.no_grad():
        for start in range(0, len(data.grade), batch):
            sl = slice(start, start + batch)
            out.append(model(data.hold_ids[sl], data.numeric[sl], data.mask[sl], data.angle[sl]))
    return torch.cat(out).numpy().astype(np.float64)


def train(
    train_data: Encoded,
    val_data: Encoded,
    vocab_size: int,
    *,
    seed: int,
    epochs: int,
    patience: int,
    batch: int,
    lr: float,
    weight_decay: float,
    hidden: int,
    log=print,
) -> tuple[DeepSets, list[dict]]:
    """Early stopping on val MAE; returns the best-epoch model and the per-epoch history."""
    torch.manual_seed(seed)
    generator = torch.Generator().manual_seed(seed)
    model = DeepSets(vocab_size, hidden=hidden, grade_mean=float(train_data.grade.mean()))
    optimizer = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=weight_decay)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs)
    loss_fn = nn.HuberLoss(delta=1.0)
    best_state, best_mae, waited, history = None, float("inf"), 0, []
    n = len(train_data.grade)
    for epoch in range(1, epochs + 1):
        model.train()
        order = torch.randperm(n, generator=generator)
        total = 0.0
        for start in range(0, n, batch):
            idx = order[start : start + batch]
            pred = model(
                train_data.hold_ids[idx],
                train_data.numeric[idx],
                train_data.mask[idx],
                train_data.angle[idx],
            )
            loss = loss_fn(pred, train_data.grade[idx])
            optimizer.zero_grad()
            loss.backward()
            optimizer.step()
            total += float(loss) * len(idx)
        scheduler.step()
        val_mae = float(np.mean(np.abs(predict(model, val_data) - val_data.grade.numpy())))
        history.append({"epoch": epoch, "train_huber": total / n, "val_mae": val_mae})
        log(f"epoch {epoch}: train_huber={total / n:.4f} val_mae={val_mae:.4f}")
        if val_mae < best_mae - 1e-4:
            best_mae, waited = val_mae, 0
            best_state = {k: v.detach().clone() for k, v in model.state_dict().items()}
        else:
            waited += 1
            if waited >= patience:
                break
    model.load_state_dict(best_state)
    return model, history
