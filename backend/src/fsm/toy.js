async function createFSM() {
  const ps = await import('../../purescript/output/Main/index.js');
  return {
    step: (state, event) => ps.step(state)(event)
  };
}

module.exports = { createFSM };