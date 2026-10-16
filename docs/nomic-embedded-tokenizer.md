# Embedded Nomic v2-MoE tokenizer

This unit supplies **tokenization/preprocessing**, not model inference. The
checkout's default `EmbeddingService` still calls HTTP; switching the provider,
provisioning local weights/runtime, pooling and native 768-coordinate inference
are separate integration work. No external server is required to load or use
this tokenizer. Do not interpret the passing tokenizer/build checks as a tested
IntelliJ Minecraft launch or a successful local embedding.

## Pinned model data

Selected upstream: [`nomic-ai/nomic-embed-text-v2-moe` at
`1066b6599d099fbb93dfcb64f9c37a7c9e503e85`](https://huggingface.co/nomic-ai/nomic-embed-text-v2-moe/tree/1066b6599d099fbb93dfcb64f9c37a7c9e503e85).
The unchanged `tokenizer.json` has SHA256
`3a56def25aa40facc030ea8b0b87f3688e4b3c39eb8b45d5702b3a1300fe2a20`.
It is packaged (gzip, about 3.7 MiB) under `assets/mysticism/embedding`, together
with Apache-2.0 license and provenance notice. Loading validates the hash before
parsing. Wrong/old/truncated assets fail closed; no tokenizer downloads, software
installation, native tokenizer dependencies or fabricated vocabularies.

This is the **250,002-entry XLM-R Unigram** tokenizer in the v2 model, not the
v1 BERT/WordPiece vocabulary. The pipeline is its precompiled SentencePiece
character rewrite map, Unicode WhitespaceSplit, Metaspace (`▁`, always prepend),
Unigram Viterbi with upstream first-path tie policy and unknown fusion, then
XLM-R BOS/EOS. Added special tokens are extracted before normalization. IDs are
preserved verbatim: BOS=0, PAD=1, EOS=2, UNK=3, MASK=250001. No lowercasing,
`[CLS]`/`[SEP]`, byte fallback, guessed vocabulary offsets or generic-NFKC
substitution. The charsmap is important: generic Java NFKC is not equivalent.
Its short-grapheme rewrite behavior depends on Unicode boundaries. Java 21's
`\\X` uses older Unicode rules and produced 78 mismatches in a 5,479-case
reference probe. `UnicodeGraphemes` instead uses pinned Unicode 17 UAX #29 data
matching the selected HF reference implementation, including Indic conjuncts,
new combining marks, emoji ZWJ sequences and regional indicators. The 2.7 KiB
gzip property table expands to 1.1 MiB, is hash-checked on load and ships with
Unicode License V3. No JDK-specific normalization or segmentation fallback.

Algorithm/reference sources: [HF Unigram](https://github.com/huggingface/tokenizers/blob/v0.23.2/tokenizers/src/models/unigram/model.rs),
[precompiled normalizer](https://github.com/huggingface/tokenizers/blob/v0.23.2/tokenizers/src/normalizers/precompiled.rs),
[SentencePiece charsmap](https://github.com/huggingface/spm_precompiled/blob/master/src/lib.rs),
[Metaspace](https://github.com/huggingface/tokenizers/blob/v0.23.2/tokenizers/src/pre_tokenizers/metaspace.rs).

## In-process provider integration

```java
// Load once on the model initialization worker, not on a Minecraft tick.
NomicV2Tokenizer tokenizer = NomicV2Tokenizer.bundled();
// Raw canonical descriptor: do NOT pre-add a task prefix.
var input = tokenizer.encodeDocument(descriptor);
long[] ids = input.inputIds();
long[] mask = input.attentionMask();
// Supply type IDs only if the selected runtime/model declares that tensor.
long[] types = input.tokenTypeIds();
```

The immutable tokenizer can be shared across workers. `encodeDocuments` returns
rows padded to the longest actual sequence in the batch, using pad ID 1 and
attention mask 0. BOS/EOS and literal added tokens are attended. Type IDs are
zero. Tensor arrays are defensively copied. Use the same attention mask in
attention and mean pooling: pads must not contribute. These are input tensors,
not embeddings; this unit does not change dimensions, normalize/truncate
embedding vectors, migrate old embeddings, alter per-player projections or
change terrain ownership/collision.

`encodeDocument` and the HTTP provider share `prepareDocument`: exactly
`search_document: ` plus the raw descriptor, preserving case and Unicode.
There is **no v1 clustering prefix**. `encode` accepts an already prepared model
input without adding a task prefix (also useful for reference checks/queries).
The model's 512-token limit includes task prefix and BOS/EOS. Overflow fails
explicitly, never clips or silently chunks. Unpaired UTF-16 surrogates and
oversized/blank raw descriptors are rejected rather than replaced silently.
Batch preparation fails as a whole if any row exceeds the context limit.

The local provider/profile integration should pin this tokenizer hash along
with the actual model revision and use the native v2 profile. It must not claim
an old Ollama manifest identifies a different local model/normalization pipeline.
No v1 archive/reindex or dimensionality change is part of this unit.

## Regression checks

`src/runtimeTest/java/io/github/mysticism/embedding/NomicV2TokenizerTest.java`
is discovered by `runtimeSelfTest` (and therefore `check`/`build`). Checked-in
fixtures contain 2,058 exact token-ID reference cases from **HF tokenizers
0.23.2 loading this full pinned tokenizer** with truncation/padding disabled:
multilingual text, accents/compatibility rewrites, control/Unicode whitespace,
Metaspace, special tokens, adjacent unknowns, deterministic Unicode fuzz and
512-token boundaries. All 766 official Unicode 17 grapheme-break conformance
cases are checked both as boundaries and as reference model input. Other checks
cover document prefix, padding/masks/type
IDs, rejection of incompatible assets/overflow/malformed scalars, defensive
copies and shared-worker deterministic behavior. The build needs no Python.

To regenerate fixtures, use an **already installed** HF tokenizers environment:

```sh
/path/to/existing/python tools/generate-tokenizer-fixtures.py /path/to/pinned/tokenizer.json
```

This developer-only generator neither installs software nor downloads assets.
It verifies the source hash and reference version before writing fixtures. No
reference package, fixture JSON or generator is shipped as an inference dependency.

Unicode inputs come from the versioned [Unicode 17 UCD](https://www.unicode.org/Public/17.0.0/ucd/):
`auxiliary/GraphemeBreakProperty.txt`, `emoji/emoji-data.txt`,
`DerivedCoreProperties.txt`, and `auxiliary/GraphemeBreakTest.txt`, together with
[Unicode License V3](https://www.unicode.org/license.txt). To regenerate from
already provisioned files (no downloads or installations):

```sh
python3 tools/generate-tokenizer-unicode.py /path/to/ucd-files
```

The generator pins every input hash. The uncompressed combined property-table
SHA256 is `23a1412313b4e74dbb1718ee448cfadd8c8a1ef262400b1b151be3590ab09d94`.

Canonical verification uses cached Gradle 8.13/JDK 21, the project's complete
current runtime discovery and packaging suites:

```sh
./gradlew --offline --no-daemon --console=plain --continue --rerun-tasks build runtimeSelfTest buildSelfTest verifyProductionJar
```

Run long commands using the assigned tmux-jobs skill and retain their pane/run
handles. The complete build discovers five current runtime suites: callback
isolation, engine safety, persistence/network, pipeline, and this tokenizer.
Historical terrain/projection prototype tests excluded by the existing build
configuration were not claimed as executed. Packaging tests and production-jar
verification also passed; a separate invocation of `NomicV2TokenizerTest` using
only the production jar, its existing Gson dependency and test outputs confirmed
that the full tokenizer and Unicode data survive remapping/packaging. The broader
5,479-case HF differential probe then had zero mismatches.

Gameplay, native model inference, tensor consumption by the eventual local
provider and numerical parity of produced embeddings remain unverified. The
checkout still selects HTTP inference; this scoped unit does not remove that
prerequisite from the full Minecraft launch. Fresh native-v2 provider/profile
integration must consume these tensors and verify real weights/inference before
claiming the overall in-process embedding requirement complete.
