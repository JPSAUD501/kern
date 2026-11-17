# Research: swipe (gesture) typing

Status: research, 2026-11-17. Input to the open decision "swipe approach" in `docs/decisions.md`.

Legend used throughout:

- **[V]** verified: read in the primary source (paper text, LICENSE file, dataset card, source code or API metadata) on 2026-11-17.
- **[S]** secondary: taken from a search summary or a third party, not read in the primary source.
- **[I]** inference or recommendation by the author of this document.

Nothing in this document is legal advice. The license readings are careful, but anything marked as a risk should get a real review before 1.0.

---

## 1. Summary and recommendation

**Recommended approach [I]:** train our own small, **layout-conditioned CTC encoder** that is in the same family as the 2026 FUTO Swipe paper. Pair it with a **trie-constrained beam search over the union of the enabled languages' lexicons**, rescored by **Kern's own multilingual language model**, which is shared with tap typing. Train it on the desktop from permissively licensed real swipes (swipe.futo.org, How We Swipe) plus geometric co-augmentation. Use synthetic swipes only as a complement, never as a replacement, and collect our own real swipes for Portuguese and Italian.

Why this approach:

1. **It is the best published open result.** On the same English test data, the FUTO encoder reaches 92.9% top-1 against 80.1% for SHARK2 template matching. SHARK2 is the algorithm family used by FlorisBoard and AnySoftKeyboard, and it is the plan for HeliBoard's new library. It is also tiny (635K parameters, 2.6 MB in fp32) and fast: 2.1 ms end to end on a Pixel 4 big core and 10 ms on a little core. Kern's bar is 40 ms p95. [V] ([FUTO paper](https://arxiv.org/html/2606.25247v1))
2. **One model serves every layout.** The keyboard geometry is a runtime input, so QWERTY, AZERTY, QWERTZ, Spanish and Kern's own split and one-handed layouts need no retraining. On Russian JCUKEN, which the model never saw in training, it still reaches 83.1% top-1 (SHARK2 gets 59.3%). [V] ([FUTO paper](https://arxiv.org/html/2606.25247v1)) This fits product priority 4: the architecture never assumes a language.
3. **Language lives only in the lexicon and the LM.** The spatial model emits key probabilities, not letters of a language. Mixed-language input then becomes a decoding and LM problem that Kern has to solve for tap typing anyway. [I]
4. **We can train it legally and on our hardware.** The real data we need is MIT-licensed. The FUTO paper says training never needed more than one workstation GPU [V] ([swipe.futo.tech](https://swipe.futo.tech/)). A third party trained a comparable 529K-parameter model in about 16 minutes on one RTX 4070 Super [S] ([youcoded/swipe-typing](https://huggingface.co/youcoded/swipe-typing)). An RTX 3070 is enough [I].

**What we must not do [V for the license text, I for the conclusions]:**

- **Do not ship FUTO's model weights**, and do not distill from them or label data with them.
  - They are under the *FUTO Model Weights License 1.0*. It is not an OSI license.
  - It requires a visible "powered by FUTO Swipe" notice to end users.
  - It defines "Derivative Model" to include any model "trained in whole or in part on Model Outputs of the Weights".
  - Source: [LICENSE.md](https://huggingface.co/futo-org/futo-swipe/raw/main/LICENSE.md).
- **Do not link or copy FUTO's `swipe-library`.** It is GPL-3.0 [V] ([LICENSE](https://gitlab.futo.org/keyboard/swipe-library/-/raw/master/LICENSE)). That is incompatible with an Apache-2.0 Kern, and D3 says Kern is built from scratch anyway. We can study its ideas, which are published in a CC BY 4.0 paper.

**Datasets we can legally train on and ship models from [V]:**

| Dataset | License | Real swipes | Languages |
|---|---|---|---|
| swipe.futo.org (swipe-1 to swipe-5) | MIT | about 1.04M (swipe-1) plus about 175K | Mostly EN QWERTY. swipe-5 adds small DE, FR, ES and other sets |
| How We Swipe (Leiva et al. 2021) | MIT (OSF project license) | about 109K swipes, 1,338 users | EN only |
| futo-org/swipe-negatives | Apache-2.0 | none (hard-negative word lists) | EN QWERTY |

There is **no open real swipe data for Portuguese or Italian**. There is very little for German, French and Spanish: under 3,000 swipes per layout in swipe-5, by the paper's own count.

**Main risks:** see §8. In short:

- accent homographs (esta/está, a/à, si/sí) that the gesture cannot separate;
- thin non-English data;
- per-language score calibration;
- active Google patents on neural gesture decoding;
- Kern's "no project server" rule, which complicates collecting our own data.

---

## 2. Q1: State of the art in gesture decoding

### 2.1 Lineage

| Work | Approach | Key facts |
|---|---|---|
| SHARK2, Kristensson & Zhai, UIST 2004 ([doi](https://doi.org/10.1145/1029632.1029640)) | Template matching. Shape channel plus location channel against ideal key-center polylines, with a frequency prior | Layout-free. Weak on words whose templates trace similar paths [V, as described in the FUTO paper] |
| Alsharif et al., Google, ICASSP 2015 ([doi](https://doi.org/10.1109/ICASSP.2015.7178336)) | LSTM trained with CTC plus FST decoding | Inputs: x, y, time and gesture type. "Considerable improvement" over shape matching [S]. Tied to one layout [V, per FUTO paper] |
| Gboard, Google AI blog, 2017 ([blog](https://research.google/blog/the-machine-intelligence-behind-gboard/)) | One LSTM spatial model trained with CTC for taps and glides, inside an FST decoder (lexicon transducer plus n-gram LM, beam search) | Fewer wrongly decoded gestures offline (about 10%). Final models were "6 times faster and 10 times smaller" than the first ones. Trained with semi-supervised signals from real users. Supports trilingual input [V] |
| Apple, Mehra et al., ICASSP 2020 ([arXiv](https://arxiv.org/abs/2004.07800)) | bi-LSTM trained with CTC. GAN-generated paths used as augmentation | 2.2M real user paths from 665 users on 6 layouts. Results in §4 [V] |
| IndicSwipe, Biju et al., COLING 2020 ([arXiv](https://arxiv.org/pdf/2203.14049)) | Transformer plus RNN with CTC. Synthetic minimum-jerk data | Indic scripts [S] |
| Gboard Neural Search Space, EMNLP 2024 ([ACL](https://aclanthology.org/2024.emnlp-industry.93/)) | Replaces the n-gram LM in the decoder FST with a neural LM, expanded at runtime | Words Modified Ratio down 0.26% to 1.19% across locales "with acceptable latency increases". The abstract does not separate gesture from tap [V, abstract only] |
| Grammarly iOS, 2024 ([blog](https://www.grammarly.com/blog/engineering/deep-learning-swipe-typing/)) | LSTM seq2seq with attention, 2 plus 2 layers, 33 features per point. Its letter output goes into the tap autocorrect, which reranks with a neural LM | 7.5M parameters. iOS keyboard memory budget about 60 MiB. Target about 17 ms. Trained on How We Swipe, WordGesture-GAN synthetic data and internal data. No accuracy numbers given [V] |
| Gesture2Text, 2024 ([arXiv](https://arxiv.org/abs/2410.18099)) | Neural decoder pretrained on coarsely discretized trajectories, for XR | 4 MB quantized, 97 ms on a Quest 3, 90.4% top-4 [V, abstract] |
| proshian/neural-swipe-typing ([GitHub](https://github.com/proshian/neural-swipe-typing)) | Transformer encoder-decoder for Russian (Yandex Cup 2023) | **No LICENSE file** [V], so not reusable |
| **FUTO Swipe**, Miller & Kostarevas, June 2026 ([arXiv](https://arxiv.org/html/2606.25247v1), CC BY 4.0) | Layout-agnostic 1D ConvNeXt-style TCN trained with CTC. A DCT spatial head is evaluated at the key centers. Trie-constrained beam search | Details below [V] |
| CHI 2025, "LLM Powered Text Entry Decoding and Flexible Typing on Smartphones" ([doi](https://doi.org/10.1145/3706598.3714314)) | LLM-based decoder for taps and gestures | Beats SHARK2 on real gestures [S]. Not checked for on-device feasibility |

**Microsoft SwiftKey:** I found no published paper on how SwiftKey Flow decodes. The FUTO paper also lists SwiftKey only as a system "trained on internal corpora" and gives no citation [V]. So no public evidence exists for SwiftKey's architecture.

### 2.2 FUTO Swipe in detail, the most relevant reference [V]

All of the following is from the [paper](https://arxiv.org/html/2606.25247v1) and the [model card](https://huggingface.co/futo-org/futo-swipe).

**Input**
- The trajectory is resampled to 60 Hz and then to 64 points.
- A Savitzky-Golay filter inside the graph produces 8 features per point: position, velocity, acceleration, speed and curvature.

**Encoder**
- 5 ConvNeXt-style 1D blocks (dilations 1, 2, 3, 5, 8; width 128), then a stride-2 adapter that takes 64 steps down to 32.
- Two heads:
  - a scalar "intention" gate λ, where blank = 1 − λ;
  - 64 DCT coefficients (an 8×8 basis).
- Per-key logits are the DCT field sampled at each key center. Keys come from `layout_keys` [1, 64, 2], normalized to [0,1]², with a mask. Up to 64 keys are supported.
- Loss: CTC plus a regularizer on the emission count.

**Augmentation (the key idea)**
- Every geometric transform is applied to both the trajectory and the key tensor:
  - y-scale and x-scale;
  - shear;
  - axis flips (p = 0.5);
  - full rotation;
  - translation;
  - time reversal (p = 0.1, which also reverses the target word).
- Without it, held-out Russian top-1 is 40.5% and ClearFlow is 3.2%. With it, they are 77.2% and 96.5%. English barely changes (93.9% to 93.3%).

**Optional per-layout decoder**
- A DFSMN with 304K parameters, trained with CTC, a ranking loss and CR-CTC. It is English QWERTY only.
- It adds about 0.6 to 0.8 points of top-1.

**Optional context LM**
- 1.5M parameters, a hash-embedding DFSMN.
- "not evaluated in this paper".

**Scoring**

`s(w|y) = −CTC(w|y)/L_w^γ + λ_f·log f_w + β·L_w (+ α·s_LM)`

- Beam width 100 with length-aware pruning. Width 100 is saturated: going to 400 adds only fractions of a point.

**Results (English QWERTY validation set, n = 52,629)**

| Setup | Top-1 | Top-3 | Top-10 |
|---|---|---|---|
| SHARK2 | 80.05 | 90.47 | 94.54 |
| FUTO encoder | 92.94 | 97.46 | 98.60 |
| FUTO encoder + decoder (test split) | 93.30 | 97.97 | 99.16 |

**Size and latency** (Pixel 4, single thread, p50, including resampling and width-100 beam search)

| Component | Parameters | Size | A76 core | A55 core |
|---|---|---|---|---|
| Encoder | 635K | 2.6 MB fp32 (1.0 MB int8 on the card) | 2.13 ms end to end | 10.1 ms end to end |
| Decoder | 304K | 1.2 MB | 2.78 ms end to end with encoder | 14.0 ms end to end with encoder |

- Runtime: ExecuTorch with XNNPACK.

**Limitations stated by the authors**
- Real data is English QWERTY only.
- Donors are self-selected.
- The vocabulary is "not held out by construction": the trie is extended with the evaluation targets.
- No WER is reported.
- There is no comparison with Gboard or other commercial keyboards.

### 2.3 What is realistic on a phone in 2026 [I, grounded in the numbers above]

- **Architecture:** a convolutional or convolutional-plus-recurrent encoder trained with CTC over keys. It has 0.5 to 1M parameters and stays at or under 3 MB in fp16 or int8, and it feeds a lexicon-constrained beam search.
  - The independent youcoded model, with 2 conv layers, a 2-layer biGRU and a key "pointer" head, lands in the same place: 529K parameters, about 3.5 ms per swipe on one CPU thread, 90.4% top-1 on its English benchmark [S] ([card](https://huggingface.co/youcoded/swipe-typing)).
  - Seq2seq transformers or LSTMs (Grammarly, 7.5M) are 10× larger. On open data they show no better accuracy, and their output alphabet ties them to a language.
- **Latency:** the encoder plus beam search costs a few ms on a big core. The budget that matters is the LM rescoring of the beam's N-best and the union lexicon across up to 6 languages. Both fit easily inside Kern's 40 ms p95 if the beam stays near 100 and the LM scores only the final N-best (for example the top 10 to 20).
- **Memory:** a few MB of model plus the lexicon tries, which Kern needs for tap typing anyway. It fits the 120 MB RSS target.

---

## 3. Q2: Open datasets and licenses

### 3.1 swipe.futo.org [V]

- **License:** MIT, `Copyright (c) 2025 FUTO`. Sources: the [LICENSE file](https://huggingface.co/datasets/futo-org/swipe.futo.org/raw/main/LICENSE) in the dataset repo, the card metadata `license: mit`, and the paper ("released under the MIT license").
- **Contents** ([dataset card](https://huggingface.co/datasets/futo-org/swipe.futo.org/raw/main/README.md); sizes from the HF tree API):

| Subset | Swipes | Text source | Notes |
|---|---|---|---|
| swipe-1 | about 1.04M after about 5% filtering (train 940K / dev 54K / test 50K, split by session) | Wikipedia sentences via Mozilla Common Voice | EN QWERTY. 108,759 unique words, 12,278 sessions. `train.jsonl` is 5.16 GB |
| swipe-2 | 28,095 | Amazon reviews, TV dialogue | Informal register, unfiltered |
| swipe-3 | 38,228 | 30% random 5-word strings, 70% Urban Dictionary / OpenWebText | Maximizes unique words, includes misspellings, unfiltered |
| swipe-4 | 50,300 | Confusable word sequences | Hard negatives, unfiltered |
| swipe-5 | 59,247 | Common Voice sentences (EN, DE, ES, FR, LT, Shavian, Toki Pona, PL) | 11 layouts incl. `azerty`, `qwertz`, `german`, `spanish`, `qwerty`. Adds `language`, `layout` and `dual_finger` fields. Unfiltered |

- **Record schema:** `word`, `sentence`, `word_idx`, `canvas_width/height`, `orientation`, `data` (list of {x, y, t} with x and y normalized to the keyboard), `session` (an anonymous UUID) and `distance` (a validity heuristic).
- **Collection:** volunteers on a mobile web page, with no live decoding.
- **Per-language counts in swipe-5:** not published. The paper says that apart from ClearFlow (11,028), "the remaining ten layouts there are smaller (under 3,000 swipes each)" [V]. The HF filter API was failing when I tried to count by `language` [V, attempt failed], so exact DE, FR and ES counts are still to be measured on the desktop.
- **Portuguese and Italian:** absent [V, from the listed layouts and sentence files].

**Can a model trained on it ship in an Apache-2.0 or GPL-3.0 app?**

- MIT is permissive and compatible with both Apache-2.0 and GPL-3.0 [V, from the license text]. The only condition is that the copyright and permission notice is "included in all copies or substantial portions of the Software".
- Whether trained weights are a "substantial portion" of a dataset is legally unsettled [I]. The conservative and cheap path: ship FUTO's MIT notice in the app's open-source licenses screen and in the model card. The youcoded model does exactly this [S].

**Text-rights caveat [I]:**
- Prompt sentences from Common Voice are CC0. Mozilla extracted at most 3 sentences per Wikipedia article, cleared with its legal team [S] ([cv-sentence-extractor](https://github.com/common-voice/cv-sentence-extractor)).
- Sentences in swipe-2 and swipe-3 come from Amazon reviews, Urban Dictionary and OpenWebText, so their copyright is unclear.
- We do not need to redistribute sentences. Kern should never republish the `sentence` field in its own artifacts, such as a benchmark release. It can publish only the IDs.

### 3.2 How We Swipe (Leiva, Kim, Cui, Bi, Oulasvirta; MobileHCI 2021) [V]

- **License:** MIT. The OSF project `sj67f` declares the MIT license with copyright holders "L. A. Leiva, S. Kim, W. Cui, X. Bi, A. Oulasvirta", 2021 ([OSF API](https://api.osf.io/v2/nodes/sj67f/), [OSF project](https://osf.io/sj67f/)).
  - The companion code repo [luileito/swipetest](https://github.com/luileito/swipetest) has **no license** [V]. We do not need it.
- **Size:**
  - 8,831,733 touch points, 11,318 unique English words, 1,338 users ([paper](https://luis.leiva.name/web/docs/papers/shapewriting-mobilehci2021-preprint.pdf)).
  - The FUTO paper counts 109,338 swipes.
  - `swipelogs.zip` is 69.9 MB on OSF.
- **Collection:** a web QWERTY keyboard on the participant's own phone, 16 sentences. Logged fields: timestamp, x and y, touch radius, keyboard geometry, plus demographics (age, gender, nationality, dominant hand, swipe familiarity).
- **Language:** English only.
- **Notes:** the layout was "customized to a mobile phone" ([RingGesture, arXiv 2410.18100](https://arxiv.org/pdf/2410.18100)) [S]. That is not a problem for a layout-conditioned model as long as the logged key geometry is used.

### 3.3 Others

| Dataset | License | Usable? |
|---|---|---|
| [futo-org/swipe-negatives](https://huggingface.co/datasets/futo-org/swipe-negatives) | Apache-2.0 [V]. Mined with SwipeALot, which is also Apache-2.0 ([card](https://huggingface.co/dleemiller/SwipeALot-base)) [V] | Yes, for EN hard-negative mining or ranking loss. Not derived from FUTO Swipe weights, so the FUTO model license does not apply [V, from the card text] |
| Yandex Cup 2023 NeuroSwipe (Russian) | Unknown. The Kaggle mirror says "Unknown" [S] ([Kaggle](https://www.kaggle.com/datasets/sharthz23/yandex-cup-2023-neuroswipe)) | No. Not a launch language either |
| IndicSwipe (synthetic, 7 Indic languages) | No license in [iitmnlp/indic-swipe](https://github.com/iitmnlp/indic-swipe) or the AI4Bharat copy [V] | No |
| WordGesture-GAN dataset (38K gestures) | Not verified | Unknown. Do not use until verified |
| HeliBoard / NLnet gesture data | Collection closes 2026-11-30. Publication license **not stated** [V] ([wiki](https://github.com/HeliBorg/HeliBoard/wiki/Tutorial:-How-to-Contribute-Gesture-Data), [issue #2226](https://github.com/HeliBorg/HeliBoard/issues/2226)). The library developer says the library will "most likely" be Apache-2.0 [V] | Watch this. It could be the only open multi-language set with real non-English swipes from Android users. **Action:** ask the maintainers about the data license |
| Gboard, Apple, SwiftKey, Grammarly | Private | No |

### 3.4 FUTO Swipe components [V]

| Component | License | Implication for Kern |
|---|---|---|
| Dataset | MIT | Usable, see above |
| Model weights (`futo-org/futo-swipe`) | **FUTO Model Weights License 1.0** ([text](https://huggingface.co/futo-org/futo-swipe/raw/main/LICENSE.md)) | Not OSI. Rules: mandatory end-user "powered by FUTO Swipe" notice; limited trademark license; derivatives must carry the license; Texas law; termination on violation. Including a non-OSI component in Kern breaks the "100% open source" promise (D1) [I]. **Allowed:** running the weights in an internal benchmark, citing them as "FUTO Swipe Model" if we publish results |
| `swipe-library` (C++, ExecuTorch) | GPL-3.0 ([LICENSE](https://gitlab.futo.org/keyboard/swipe-library/-/raw/master/LICENSE)), commit `e8ade0e`, 2026-11-13 | Incompatible with an Apache-2.0 Kern. Compatible with GPL-3.0, but D3 rules out reuse anyway |
| FUTO Keyboard app | FUTO Source First License 1.1-kb ([LICENSE.md](https://github.com/futo-org/android-keyboard/blob/master/LICENSE.md)): modification only for non-commercial purposes, redistribution only free of charge | Not open source. Study only |
| Paper | CC BY 4.0 | Methods are freely usable with attribution |

---

## 4. Q3: Synthetic swipe generation

### 4.1 Models of human motion

- **Minimum jerk** (Flash & Hogan 1985). Quinn & Zhai, *Modeling Gesture-Typing Movements*, HCI 2018, used it to produce gesture shapes and dynamics through statistical via-points that model sensorimotor noise ([Google Research](https://research.google/pubs/pub45730)) [V, abstract]. It is used to generate training data by IndicSwipe and PhraseSwipe (UIST 2022) [S], and by FUTO for layout search [V].
- **Sigma-lognormal (kinematic theory).** Leiva, Martín-Albo and Plamondon, *Gestures à Go Go*, ACM TIST 2016 ([doi](https://dl.acm.org/doi/pdf/10.1145/2799648)) [S]. It synthesizes human-like strokes from a single example. For stroke-gesture recognizers, it has been shown to match training on human samples [S] ([Taranta et al., UIST 2016](https://www.eecs.ucf.edu/~jjl/pubs/uist2016-taranta.pdf)). **I found no published result that trains a word-gesture keyboard decoder on sigma-lognormal data.**
- **Cubic splines** through key centers: Apple's "synthetic" baseline [V].
- **GANs:** Apple's multi-task style-transfer GAN [V]; WordGesture-GAN, CHI 2023 ([doi](https://doi.org/10.1145/3544548.3581279)) [S]; GAN-Imitation and GAN-Transfer, ISMAR 2021 (Shen, Dudley, Kristensson) [V, conclusions section].

### 4.2 Measured transfer from synthetic to real swipes

**Apple, Mehra et al. 2020, Table 1** [V] ([PDF](https://arxiv.org/pdf/2004.07800)).
Setup: 59,469 real test paths, 55K lexicon, **no LM**, top-1.

| Training data | Top-1 |
|---|---|
| 1.1M real | 58.5% |
| 1.1M spline-synthetic only | **35.0%** |
| 1.1M GAN only | 33.7% |
| 2.2M real | 62.2% |
| 1.1M real + 1.1M spline | 57.6% (*worse* than real alone) |
| 1.1M real + 1.1M GAN | 62.4% (about as good as doubling real data) |
| 2.2M real + 10.8M GAN | 66.8% |

Conclusion from the paper: GAN data only helps as a complement at around 5:1 GAN to real, and naive synthetic data "completely fails to help".

**FUTO 2026** [V]:
- Adding about 170K minimum-jerk-style IndicSwipe synthetic swipes left English flat (93.39 vs 93.25) and slightly hurt held-out Russian (76.68 vs 77.15) and ClearFlow (96.32 vs 96.45).
- The authors judge the synthetic data "too easy at training time to contribute meaningful gradient". Greedy CTC got 96.5% on held-out synthetic Tamil.
- Their caution: this is evidence against synthetic data "as a substitute for real data", not against multi-layout training.
- In the other direction, minimum-jerk NLL was a faithful proxy for ranking *layouts* (KASROZ reached 97.68% top-1 on real swipes after optimizing on synthetic ones).

**WordGesture-GAN, CHI 2023** [S, numbers from a search snippet of Table 7, not read in full text]: SHARK2 WER was 32.8% with 200 real gestures, 28.6% with 200 real plus 10,000 simulated, and 27.8% with 10,000 real. Note that SHARK2 has few parameters to fit, so this says little about neural decoders.

**ISMAR 2021, Dudley et al.** [V, conclusions]: for training recognizers, GAN-Imitation and GAN-Transfer are closer to real data in latent space than jerk minimization. Jerk minimization is preferred for layout design because it adapts to new layouts without data. The paper reports no decoder accuracy.

### 4.3 What this means for Kern [I]

- **Synthetic-only training will not reach the quality bar.** On Apple's numbers it loses about 40% of top-1 relative to real data.
- **Synthetic data is useful for:**
  1. covering **words** absent from real data, for example PT and IT lexicons swiped on an existing layout geometry, mixed at a low ratio;
  2. **geometry** that real data does not cover (split and one-handed layouts), although co-augmentation of real swipes already gives most of this;
  3. **pre-training** before fine-tuning on the little real non-English data.
- **A useful property of a layout-conditioned model:** a Portuguese word swiped on QWERTY is just a key sequence on a known layout. English real data already teaches the motor behavior of "drawing through these keys". The open question is not *motion* but *key sequence distribution* (letter bigrams such as "ão" or "nh" that are rare in English). Data-driven style transfer (a GAN or a conditional generative model trained on swipe.futo.org) is the better generator if we need one. Minimum jerk with via-point noise is the cheap baseline. Both must be judged on **real** PT and IT test swipes, which we have to collect anyway.

---

## 5. Q4: Multilingual and multi-layout

### 5.1 Layout normalization [V for what others do, I for Kern]

- **FUTO:** key centers are normalized to [0,1]² of the letter area. Training co-augments trajectory and keys. Inference takes any key set of up to 64 keys. In code, the layout JSONs bundled with `swipe-library` (`de_qwertz`, `fr_azerty`, `es_spanish`, `en_qwerty`, ...) list only `a–z`, plus `ñ` for Spanish [V, read at commit `e8ade0e`].
- **youcoded:** scores each key from the finger's offset to the key and the key's size, which gives one set of weights for QWERTY, AZERTY, QWERTZ and Spanish [S].
- **Gboard:** the 2015 and 2017 LSTM approach puts the layout into input features, so it is tied to one layout [V, per the FUTO paper].
- **Kern [I]:** use the same "keys as runtime input" design. Normalize by the letter-key bounding box. Feed per-key center and half-size, since Kern's adaptive touch model may move key targets. Add split-keyboard geometry to the augmentation, because the FUTO recipe does not include a gap between halves.

### 5.2 Characters with diacritics (ç, ã, é, ß, ñ, ü)

The spatial model emits **keys**, not characters. Each lexicon word therefore needs a **word → key sequence** mapping per layout [I]:

1. **The character has its own key on the layout.** Examples: `ñ` on Spanish, `ü/ö/ä` on German layouts that show them, `ç` on layouts that have it. Map to that key.
   - Allow the base key as an alternative path too, because users of a layout with umlaut keys may still trace `u` [I, needs data to confirm].
2. **The character is a long-press variant** (é, è, ê, ã, õ, à, ì, ...). Map it to the base key using Unicode NFD decomposition.
   - FlorisBoard already does this: it uses `Normalizer.Form.NFD` on the first and last letters and to build ideal paths [V] ([StatisticalGlideTypingClassifier.kt](https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/gestures/StatisticalGlideTypingClassifier.kt)).
3. **ß** maps to the `s` key (single), with `ss` as an alternative. **Apostrophes and hyphens** are not traced: skip them in the key sequence. FUTO normalizes apostrophe variants in its trie [V].

A pitfall seen in other projects:
- HeliBoard's wiki warns that the Google library "hard codes where letters with diacritics are expected to be" [V].
- In FUTO's bundled `load_trie_simple`, a word containing a code point that is not in the layout's `letters` is **skipped** (`trie.cpp`: "Invalid character, skip word") [V, code read, not executed]. So with the bundled `fr_azerty` (a–z only), "café" would apparently be dropped unless the app supplies its own trie. The library is "bring-your-own-dictionary" [V, README].

**Consequence: accent homographs** [I]. After folding, many pairs share the identical key sequence and are *indistinguishable by gesture*:

| Language | Examples |
|---|---|
| PT | e/é, esta/está, da/dá, pais/país, avo/avó/avô |
| ES | si/sí, el/él, tu/tú, mas/más |
| FR | a/à, ou/où, du/dû |
| IT | e/è, la/là |
| DE | dass/daß (with ß → ss) |

Only the context LM can choose, and the suggestion strip must offer the variant. This is the most important quality lever for Kern's non-English swipe. It is invisible in English-only benchmarks.

### 5.3 Mixed-language input

- **Gboard:** decodes through one FST with lexicon and n-gram transducers and advertises "seamless multilingual support" and trilingual typing [V, blog]. No details are published on how glide is scored across languages.
- **FUTO:** scoring constants are language-specific.
  - Tuning on Russian instead of English moves Russian top-1 from 83.11 to 86.59 and English from 92.94 to 90.23.
  - Normalizing frequencies by z-score or rank "did not close the gap".
  - For English the frequency prior does most of the work. For Russian, raw CTC with length normalization is better [V, Appendices F and G].
  - **This is direct evidence that a naive union lexicon with one set of weights will lose accuracy in at least one language.**
- **FlorisBoard** classifies against one subtype's word list at a time (`setWordData(subtype)`) [V, code]. It has no mixed-language glide.

**Recommended design for Kern [I]:**

1. One trie per enabled language, keyed by the folded key sequence for the active layout. Each node carries a list of (surface form, language, unigram log-probability). A shared beam searches all tries at once, or a merged trie with language-tagged leaves. Words shared across languages ("no", "come", "pain", "de") are one key path with several surfaces.
2. Spatial score: length-normalized CTC.
3. Prior: `log P(w | context)` from Kern's multilingual LM, which is also used for tap typing. Add a per-word language prior from the session's language detector (recent words), so a Portuguese sentence makes Portuguese candidates more likely without excluding an English word.
4. Use **probabilities, not per-language frequency ranks**, for priors. Tune the combination weights on a **mixed** validation set (per-language real swipes plus code-switched sentences) with an objective that is a weighted sum of per-language accuracy, so no language is sacrificed. FUTO used Optuna TPE over 4 scalar weights [V], which is cheap.
5. Rescore only the final N-best with the neural LM. That keeps latency bounded.

### 5.4 German compounds [I, with one source]

German has effectively unbounded compounds. Gboard handles compounding in tap decoding with subword units marked by a binding type, which cut WER by about 20% in compounding languages ([arXiv 2201.06469](https://arxiv.org/abs/2201.06469)) [V, abstract]. Swiping a long compound is rare, and a trie limited to the lexicon cannot output an unseen compound. For 1.0, accept this limitation and learn user-entered compounds into the personal lexicon. Compound decoding is post-1.0.

---

## 6. Q5: Open source keyboards today

| Keyboard (license) | Swipe implementation | Known weaknesses |
|---|---|---|
| **FlorisBoard** (Apache-2.0 [V]) | `StatisticalGlideTypingClassifier`, adapted from AnySoftKeyboard PR #1870: prune by first and last key and by length, then score `shape × location × frequency` Gaussians against ideal key-center paths (SHARK2-like). Unigram frequency only, one subtype at a time. Commit `bac346e`, 2026-11-02 [V] | No context LM. No multilingual glide. Accuracy limited by template matching. An issue asks to use the FUTO data ([#3329](https://github.com/florisboard/florisboard/issues/3329), open) [V] |
| **AnySoftKeyboard** (Apache-2.0 [V]) | `GestureTypingDetector`: keep only path *corners* (curvature > 170°) and use a cumulative distance with direction and proximity penalties plus a frequency factor [V, code] | PR #1870's author describes "loss of information", for example double letters, and says the method "isn't accurate enough to be a valid alternative" to tapping ([PR #1870](https://github.com/AnySoftKeyboard/AnySoftKeyboard/pull/1870), still open) [V] |
| **HeliBoard** (GPL-3.0 [V]) | Gesture typing only works if the user side-loads Google's proprietary `libjni_latinimegoogle.so` [V, wiki]. An NLnet-funded open replacement is in progress ([NLnet](https://nlnet.nl/project/GestureTyping/), [#2226](https://github.com/HeliBorg/HeliBoard/issues/2226)) | The replacement is planned as a **hand-crafted** algorithm: the developer suspects "a hand crafted algorithm will out perform ML" without a large team [V]. Users report common words losing to rare ones [V, issue comments]. No release date. Library license "most likely Apache-2.0" [V] |
| **FUTO Keyboard** (Source First License, not OSI [V]) | FUTO Swipe neural encoder plus decoder plus context LM, from v0.1.29 [S] | Not open source. Decoder and LM are English only. Non-English uses the encoder alone at "lower accuracy" [V, swipe.futo.tech]. The third-party comparison in HeliBoard reports first-guess accuracy of EN 84.5, ES 77.7, FR 64.6 and DE 69.7 for FUTO 0.3.1 [S] ([youcoded card](https://huggingface.co/youcoded/swipe-typing)): a large gap between English and other languages |

**Takeaway [I]:** no OSI-licensed Android keyboard today ships a neural swipe decoder. The open ones use SHARK2-style or corner matching with unigram priors and a single language. This is exactly the gap Kern's product doc targets.

---

## 7. Q6: Evaluation

### 7.1 Metrics in use [V]

- **Top-k accuracy** (top-1, top-3, top-10) of the decoded word. Used by FUTO and Apple (top-1, deliberately without an LM). Top-3 matches Kern's suggestion strip.
- **Word error rate.** How We Swipe reports WER and WPM for users. The Gboard compounding paper uses WER.
- **Words Modified Ratio.** Gboard's online metric: the share of committed words the user later edits. It needs live users.
- **MRR.** "Swipe MRR" was the Yandex Cup metric [S].
- **FUTO's tuning objective** is ¼(top1 + top3 + mAP@5 + macroF1@5) [V].

### 7.2 A fair benchmark for Kern [I]

**1. Splits by user or session, never by swipe.** FUTO splits by session [V]. Swipes from one person in both train and test inflate scores.

**2. Report each of these separately:**
- per language **and** per layout;
- with and without context LM;
- in-vocabulary vs out-of-vocabulary rate. FUTO's caveat is that they extended the trie with the eval targets [V]. Kern should report the OOV rate under the *shipped* lexicon, not a lexicon that contains the test words.

**3. Stratify by:**
- word length;
- confusable sets (swipe-4, swipe-negatives);
- accent homographs (§5.2);
- code-switched contexts (an EN word inside a PT sentence).

**4. Use identical lexicon and frequencies for every system compared.** FUTO's appendix shows that frequency and length weights alone swing results by several points [V].

**5. Baselines:**
- Our own SHARK2 re-implementation (the FUTO paper gives tuned constants in Appendix E [V]).
- The FUTO Swipe model plus library, run **internally** for comparison. The license allows use. Publishing results requires citing "FUTO Swipe Model" [V]. It is never shipped.
- Gboard if possible. Proposal: replay recorded test swipes into Gboard on a device by injecting motion events and read back the committed word [I, unverified feasibility].

**6. Latency on the reference device:**
- p50, p95 and p99, measured from finger lift to suggestions, including LM rescoring;
- on big **and** little cores, because the IME process may be scheduled on either.

**7. The test sets themselves:**
- **EN:** swipe.futo.org test split, plus How We Swipe as an out-of-distribution set (a different population and collection site).
- **DE, FR, ES:** swipe-5 subsets. They are small, so report confidence intervals.
- **PT, IT:** none exist. **Kern must collect real test swipes** (see risks).
- At least a few thousand swipes per language: about ±1 point at 95% confidence for top-1 near 90% with n ≈ 3,500 [I, binomial approximation].

**8. Wire it into the harness.** The product principle "measure, don't guess" means this benchmark runs in CI on every model or engine change, per language.

---

## 8. Open risks

1. **No real swipes for PT or IT, and few for DE, FR and ES.** This is the biggest risk to the "swipe as good as proprietary in every launch language" goal.
   - Options:
     - an opt-in, in-app "teach Kern" collection screen with export by email or file share (HeliBoard's model);
     - a static web page like swipe.futo.org.
   - The web page needs somewhere to receive data, which conflicts with D1's "no project-run backend" unless it is treated as separate from user text. **Needs João's call.** [I]
   - Also: ask HeliBoard and NLnet what license their 2026 multi-language collection will be released under. [I]
2. **Accent homographs** (§5.2) cannot be solved by the spatial model. Quality depends on the LM and on the strip offering the accented variant. [I]
3. **Per-language calibration.** FUTO shows tuning for one language costs about 2 to 3 points in another [V]. Mixed-language decoding needs a jointly tuned prior. [I]
4. **Patents.** Google holds US 9,678,664 B2, "Neural network for keyboard input decoding": filed 2015-04-10, listed as **Active** with adjusted expiration 2035-08-05. The family includes 10,248,313, 10,671,281, 11,150,804 and 11,573,698 ([Google Patents](https://patents.google.com/patent/US9678664B2/en)) [V for status, claims not read].
   - The summary describes determining a character string from spatial features of a gesture processed by a neural network.
   - FUTO, Grammarly and Apple ship neural decoders, but that says nothing about their licensing position. Apache-2.0's patent grant covers only contributors' patents.
   - **Needs a proper freedom-to-operate review before 1.0.** [I]
5. **License drift.** FUTO could change the dataset license for future versions. Pin the dataset by HF commit hash and keep checksums in the training scripts (D6). The MIT grant for the copy we pinned cannot be revoked. [I]
6. **Template-matching fallback.** User-added words and names should be swipeable. With a lexicon-constrained CTC beam this works as soon as the word is in the personal lexicon, so a separate SHARK2 engine is not needed. Very rare long words may still need a fallback; measure before adding one. [I]
7. **Synthetic data could mislead.** Apple and FUTO both show that naive synthetic data does not help, or hurts [V]. Every synthetic mix must be judged on real test swipes only. [I]
8. **Not covered here:** split-keyboard geometry, two-finger swipes (swipe-5 has some dual-finger data [V]) and multi-word swipes. All are post-1.0 [I].

---

## 9. Sources

Papers
- Miller & Kostarevas, *FUTO Swipe: Layout-Agnostic Neural Swipe Decoding*, arXiv 2606.25247 (2026): https://arxiv.org/html/2606.25247v1
- Leiva et al., *How We Swipe*, MobileHCI 2021: https://luis.leiva.name/web/docs/papers/shapewriting-mobilehci2021-preprint.pdf
- Mehra et al., *Leveraging GANs to Improve Continuous Path Keyboard Input Models*, ICASSP 2020: https://arxiv.org/pdf/2004.07800
- Kristensson & Zhai, *SHARK2*, UIST 2004: https://doi.org/10.1145/1029632.1029640
- Alsharif et al., *LSTM neural network for keyboard gesture decoding*, ICASSP 2015: https://doi.org/10.1109/ICASSP.2015.7178336
- Zhang et al., *Neural Search Space in Gboard Decoder*, EMNLP 2024 Industry: https://aclanthology.org/2024.emnlp-industry.93/
- Ouyang et al., *Mobile Keyboard Input Decoding with FSTs*: https://arxiv.org/abs/1704.03987
- *Handling Compounding in Mobile Keyboard Input*: https://arxiv.org/abs/2201.06469
- Quinn & Zhai, *Modeling Gesture-Typing Movements*, HCI 2018: https://research.google/pubs/pub45730
- Biju et al., *Joint Transformer/RNN Architecture for Gesture Typing in Indic Languages*, COLING 2020: https://arxiv.org/pdf/2203.14049
- Shen et al., *Gesture2Text*: https://arxiv.org/abs/2410.18099
- Chu et al., *WordGesture-GAN*, CHI 2023: https://doi.org/10.1145/3544548.3581279
- Dudley, Kristensson et al., *Simulating Realistic Human Motion Trajectories of Mid-Air Gesture Typing*, ISMAR 2021: https://api.repository.cam.ac.uk/server/api/core/bitstreams/3a9400b4-51a1-4fe3-a6cb-b581f9ef892f/content
- Leiva, Martín-Albo, Plamondon, *Gestures à Go Go*, ACM TIST 2016: https://dl.acm.org/doi/pdf/10.1145/2799648
- Taranta et al., UIST 2016: https://www.eecs.ucf.edu/~jjl/pubs/uist2016-taranta.pdf
- RingGesture, arXiv 2410.18100: https://arxiv.org/pdf/2410.18100
- *LLM Powered Text Entry Decoding and Flexible Typing on Smartphones*, CHI 2025: https://doi.org/10.1145/3706598.3714314

Industry posts
- Google AI blog, *The Machine Intelligence Behind Gboard* (2017): https://research.google/blog/the-machine-intelligence-behind-gboard/
- Grammarly Engineering, *How We Use Deep Learning for Swipe Typing* (2024): https://www.grammarly.com/blog/engineering/deep-learning-swipe-typing/
- FUTO Swipe site: https://swipe.futo.tech/

Datasets, models, licenses
- swipe.futo.org dataset card: https://huggingface.co/datasets/futo-org/swipe.futo.org (LICENSE: https://huggingface.co/datasets/futo-org/swipe.futo.org/raw/main/LICENSE)
- swipe-negatives: https://huggingface.co/datasets/futo-org/swipe-negatives
- FUTO Swipe weights and license: https://huggingface.co/futo-org/futo-swipe, https://huggingface.co/futo-org/futo-swipe/raw/main/LICENSE.md
- swipe-library (GPL-3.0): https://gitlab.futo.org/keyboard/swipe-library
- FUTO Keyboard license: https://github.com/futo-org/android-keyboard/blob/master/LICENSE.md
- How We Swipe on OSF: https://osf.io/sj67f/ (license via https://api.osf.io/v2/nodes/sj67f/)
- SwipeALot: https://huggingface.co/dleemiller/SwipeALot-base
- youcoded/swipe-typing: https://huggingface.co/youcoded/swipe-typing
- Yandex Cup 2023 NeuroSwipe mirror: https://www.kaggle.com/datasets/sharthz23/yandex-cup-2023-neuroswipe
- IndicSwipe: https://github.com/iitmnlp/indic-swipe
- proshian/neural-swipe-typing: https://github.com/proshian/neural-swipe-typing
- Common Voice sentence extractor: https://github.com/common-voice/cv-sentence-extractor

Open source keyboards
- FlorisBoard glide classifier: https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/gestures/StatisticalGlideTypingClassifier.kt, issue #3329: https://github.com/florisboard/florisboard/issues/3329
- AnySoftKeyboard gesture detector: https://github.com/AnySoftKeyboard/AnySoftKeyboard/blob/main/ime/app/src/main/java/com/anysoftkeyboard/gesturetyping/GestureTypingDetector.java, PR #1870: https://github.com/AnySoftKeyboard/AnySoftKeyboard/pull/1870
- HeliBoard NLnet issue: https://github.com/HeliBorg/HeliBoard/issues/2226, data tutorial: https://github.com/HeliBorg/HeliBoard/wiki/Tutorial:-How-to-Contribute-Gesture-Data
- NLnet GestureTyping project: https://nlnet.nl/project/GestureTyping/

Patents
- US 9,678,664 B2, *Neural network for keyboard input decoding* (Google LLC): https://patents.google.com/patent/US9678664B2/en
