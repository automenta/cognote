// util.js - Common utility functions for Confluence App

export const generateUUID = () => crypto.randomUUID();

export const cosineSimilarity = (vecA, vecB) => {
    if (!vecA || !vecB || vecA.length !== vecB.length) return 0;
    let dotProduct = 0.0;
    let normA = 0.0;
    let normB = 0.0;
    for (let i = 0; i < vecA.length; i++) {
        dotProduct += vecA[i] * vecB[i];
        normA += vecA[i] * vecA[i];
        normB += vecB[i] * vecB[i];
    }
    const divisor = Math.sqrt(normA) * Math.sqrt(normB);
    return divisor === 0 ? 0 : dotProduct / divisor;
};

// Simple visualization: Map embedding values to RGB colors for a 3x3 grid
export const generateEmbeddingVis = (embedding) => {
    if (!embedding || embedding.length < 9) {
        // Default grey grid if no/short embedding
        return Array(9).fill('rgb(128, 128, 128)');
    }
    const colors = [];
    // Scale embedding values (assuming roughly -1 to 1 range, common for normalized embeddings)
    // and map segments to RGB components. This is a *very* basic visualization.
    const scale = (val) => Math.floor(((val + 1) / 2) * 255); // Scale -1..1 to 0..255

    for (let i = 0; i < 9; i++) {
        // Use different triplets of embedding values for each color square
        const rIdx = (i * 3) % embedding.length;
        const gIdx = (i * 3 + 1) % embedding.length;
        const bIdx = (i * 3 + 2) % embedding.length;
        const r = scale(embedding[rIdx]);
        const g = scale(embedding[gIdx]);
        const b = scale(embedding[bIdx]);
        colors.push(`rgb(${r}, ${g}, ${b})`);
    }
    return colors;
};

export const debounce = (func, wait) => {
    let timeout;
    return function executedFunction(...args) {
        const later = () => {
            clearTimeout(timeout);
            func(...args);
        };
        clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
};

export const escapeHtml = (unsafe) =>
    unsafe
        .replace(/&/g, "&")
        .replace(/</g, "<")
        .replace(/>/g, ">")
        .replace(/"/g, "\"")
    .replace(/'/g, "'");

// Export functions for Node.js (server.js)
if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
        generateUUID,
        cosineSimilarity,
        generateEmbeddingVis,
        debounce,
        escapeHtml,
    };
}
// No explicit export needed for browser inclusion via <script>