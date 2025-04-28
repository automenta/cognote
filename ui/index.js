import * as THREE from 'three';
import { CSS3DObject, CSS3DRenderer } from 'three/addons/renderers/CSS3DRenderer.js';
import { gsap } from "gsap";

// --- Utilities ---
const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => document.querySelectorAll(selector);

const Utils = {
    clamp: (val, min, max) => Math.max(min, Math.min(max, val)),
    lerp: (a, b, t) => a + (b - a) * t,
    generateId: (prefix) => `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`,
    DEG2RAD: Math.PI / 180,
};

// --- Core Classes ---

class SpaceGraph {
    nodes = new Map();
    edges = new Map();

    nodeSelected = null;
    edgeSelected = null;

    isLinking = false;
    linkSourceNode = null;
    tempLinkLine = null;

    ui = null;
    camera = null;
    _cam = null; // Internal THREE.Camera reference
    layout = null;

    renderGL = null;
    renderHTML = null;
    css3dContainer = null; // Reference to the dynamically created container

    constructor(containerElement) {
        if (!containerElement) throw new Error("SpaceGraph requires a valid container element.");
        this.container = containerElement;
        this.scene = new THREE.Scene();
        this.cssScene = new THREE.Scene();

        this._cam = new THREE.PerspectiveCamera(70, window.innerWidth / window.innerHeight, 1, 20000);
        this._cam.position.z = 700; // Initial position before centering
        this.camera = new Camera(this); // Pass SpaceGraph instance

        this._setupRenderers();
        this._setupLighting();

        this.centerView(null, 0); // Center immediately based on potential initial state
        this.camera.setInitialState(); // Set initial state AFTER first positioning

        window.addEventListener('resize', this._onWindowResize.bind(this), false);
    }

    _setupRenderers() {
        const canvas = $('#webgl-canvas');
        if (!canvas) throw new Error("WebGL canvas element (#webgl-canvas) not found.");
        this.renderGL = new THREE.WebGLRenderer({ canvas: canvas, antialias: true, alpha: true });
        this.renderGL.setSize(window.innerWidth, window.innerHeight);
        this.renderGL.setPixelRatio(window.devicePixelRatio);
        this.renderGL.setClearColor(0x000000, 0); // Transparent background

        this.renderHTML = new CSS3DRenderer();
        this.renderHTML.setSize(window.innerWidth, window.innerHeight);

        // Create and append CSS3D container dynamically
        this.css3dContainer = document.createElement('div');
        this.css3dContainer.id = 'css3d-container';
        this.css3dContainer.style.position = 'absolute';
        this.css3dContainer.style.top = '0';
        this.css3dContainer.style.left = '0';
        this.css3dContainer.style.width = '100%';
        this.css3dContainer.style.height = '100%';
        this.css3dContainer.style.pointerEvents = 'none'; // Allow clicks to pass through to WebGL canvas
        this.css3dContainer.appendChild(this.renderHTML.domElement);
        this.container.appendChild(this.css3dContainer);
    }

    _setupLighting() {
        const ambientLight = new THREE.AmbientLight(0xffffff, 0.8);
        this.scene.add(ambientLight);
        const directionalLight = new THREE.DirectionalLight(0xffffff, 0.7);
        directionalLight.position.set(0.5, 1, 0.75);
        this.scene.add(directionalLight);
    }

    addNode(nodeInstance) {
        if (!nodeInstance.id) nodeInstance.id = Utils.generateId('node');
        if (this.nodes.has(nodeInstance.id)) return this.nodes.get(nodeInstance.id); // Return existing if ID matches

        this.nodes.set(nodeInstance.id, nodeInstance);
        nodeInstance.space = this; // Link back to space
        if (nodeInstance.cssObject) this.cssScene.add(nodeInstance.cssObject);
        this.layout?.addNode(nodeInstance);
        return nodeInstance;
    }

    removeNode(nodeId) {
        const node = this.nodes.get(nodeId);
        if (!node) return;

        if (this.nodeSelected === node) this.setSelectedNode(null);
        if (this.linkSourceNode === node) this.ui?.cancelLinking();

        // Remove associated edges
        const edgesToRemove = [...this.edges.values()].filter(edge => edge.source === node || edge.target === node);
        edgesToRemove.forEach(edge => this.removeEdge(edge.id));

        this.layout?.removeNode(node);
        node.dispose(); // Dispose node resources (removes from scenes)
        this.nodes.delete(nodeId);
    }

    addEdge(sourceNode, targetNode, data = {}) {
        if (!sourceNode || !targetNode || sourceNode === targetNode) return null;
        // Prevent duplicate edges (undirected check)
        if ([...this.edges.values()].some(e =>
            (e.source === sourceNode && e.target === targetNode) ||
            (e.source === targetNode && e.target === sourceNode))) {
            console.warn("Attempted to add duplicate edge between:", sourceNode.id, targetNode.id);
            return null;
        }

        const edgeId = Utils.generateId('edge');
        const edge = new Edge(edgeId, sourceNode, targetNode, data);
        this.edges.set(edgeId, edge);
        if (edge.geo) this.scene.add(edge.geo);
        this.layout?.addEdge(edge);
        return edge;
    }

    removeEdge(edgeId) {
        const edge = this.edges.get(edgeId);
        if (!edge) return;

        // Deselect if selected (this also hides the edge menu via setSelectedEdge)
        if (this.edgeSelected === edge) this.setSelectedEdge(null);

        this.layout?.removeEdge(edge);
        edge.dispose(); // Dispose edge resources (removes from scene)
        this.edges.delete(edgeId);
    }

    getNodeById = (id) => this.nodes.get(id);
    getEdgeById = (id) => this.edges.get(id);

    updateNodesAndEdges() {
        this.nodes.forEach(node => node.update(this));
        this.edges.forEach(edge => edge.update(this));
        this.ui?.updateEdgeMenuPosition(); // Update menu if visible
    }

    render() {
        this.renderGL.render(this.scene, this._cam);
        this.renderHTML.render(this.cssScene, this._cam);
    }

    _onWindowResize() {
        const iw = window.innerWidth, ih = window.innerHeight;
        this._cam.aspect = iw / ih;
        this._cam.updateProjectionMatrix();
        this.renderGL.setSize(iw, ih);
        this.renderHTML.setSize(iw, ih);
    }

    centerView(targetPosition = null, duration = 0.7) {
        let targetPos;
        if (targetPosition instanceof THREE.Vector3) {
            targetPos = targetPosition.clone();
        } else {
            targetPos = new THREE.Vector3();
            if (this.nodes.size > 0) {
                this.nodes.forEach(node => targetPos.add(node.position));
                targetPos.divideScalar(this.nodes.size);
            } else if (targetPosition && typeof targetPosition.x === 'number') {
                targetPos.set(targetPosition.x, targetPosition.y, targetPosition.z);
            }
            // else { targetPos remains (0,0,0) } Default to origin if no nodes and no specific position given
        }

        // Determine appropriate distance based on whether it's initial load or recentering
        const distance = this.nodes.size > 1 ? 700 : 400; // Simple heuristic
        this.camera.moveTo(targetPos.x, targetPos.y, targetPos.z + distance, duration, targetPos);
    }

    focusOnNode(node, duration = 0.6, pushHistory = false) {
        if (!node || !this._cam) return;
        const targetPos = node.position.clone();

        // Calculate distance to fit node bounds in view
        const fov = this._cam.fov * Utils.DEG2RAD;
        const aspect = this._cam.aspect;
        // Estimate node size projected onto screen (use diagonal for robustness)
        const nodeDiagonal = Math.sqrt(node.size.width ** 2 + node.size.height ** 2);
        // Project size onto the axis that fills the view more (height or width/aspect)
        const projectedSize = Math.max(node.size.height, node.size.width / aspect);
        const paddingFactor = 1.3; // Add 30% padding around the node
        const minDistance = 50; // Ensure camera isn't too close

        // Distance = half_size / tan(half_fov)
        const distance = Math.max(minDistance, (projectedSize * paddingFactor) / (2 * Math.tan(fov / 2)));

        if (pushHistory) this.camera.pushState();
        this.camera.moveTo(targetPos.x, targetPos.y, targetPos.z + distance, duration, targetPos);
    }

    autoZoom(node) {
        if (!node || !this.camera) return;
        const currentTargetNodeId = this.camera.getCurrentTargetNodeId();
        if (currentTargetNodeId === node.id) {
            // If already focused on this node, go back
            this.camera.popState();
        } else {
            // Focus on the new node, push current state to history
            this.camera.pushState();
            this.camera.setCurrentTargetNodeId(node.id);
            this.focusOnNode(node, 0.6, false); // History already pushed
        }
    }

    screenToWorld(screenX, screenY, targetZ = 0) {
        this._cam.updateMatrixWorld();
        const raycaster = new THREE.Raycaster();
        // Convert screen coords to normalized device coordinates (-1 to +1)
        const vec = new THREE.Vector2(
            (screenX / window.innerWidth) * 2 - 1,
            -(screenY / window.innerHeight) * 2 + 1
        );
        raycaster.setFromCamera(vec, this._cam);

        // Intersect with a plane at the target Z depth
        const targetPlane = new THREE.Plane(new THREE.Vector3(0, 0, 1), -targetZ);
        const intersectPoint = new THREE.Vector3();
        return raycaster.ray.intersectPlane(targetPlane, intersectPoint) ? intersectPoint : null;
    }

    setSelectedNode(node) {
        if (this.nodeSelected === node) return;

        this.nodeSelected?.htmlElement?.classList.remove('selected');
        this.nodeSelected = node;
        this.nodeSelected?.htmlElement?.classList.add('selected');

        // Deselect any selected edge when a node is selected
        if (node) this.setSelectedEdge(null);
    }

    setSelectedEdge(edge) {
        if (this.edgeSelected === edge) return;

        // Deselect previous edge and hide its menu
        if (this.edgeSelected) {
            this.edgeSelected.setHighlight(false);
            this.ui?.hideEdgeMenu();
        }

        this.edgeSelected = edge;

        // Select new edge and show its menu
        if (this.edgeSelected) {
            this.edgeSelected.setHighlight(true);
            this.ui?.showEdgeMenu(this.edgeSelected);
            // Deselect any selected node when an edge is selected
            this.setSelectedNode(null);
        }
    }

    intersectedObject(screenX, screenY) {
        const vec = new THREE.Vector2((screenX / window.innerWidth) * 2 - 1, -(screenY / window.innerHeight) * 2 + 1);
        const raycaster = new THREE.Raycaster();
        raycaster.setFromCamera(vec, this._cam);
        raycaster.params.Line.threshold = 5; // Raycasting threshold for lines

        const edgeObjects = [...this.edges.values()].map(e => e.geo).filter(Boolean);
        if (edgeObjects.length === 0) return null;

        const intersects = raycaster.intersectObjects(edgeObjects, false); // Don't check descendants

        if (intersects.length > 0) {
            const intersectedLine = intersects[0].object;
            return [...this.edges.values()].find(edge => edge.geo === intersectedLine);
        }
        return null;
    }

    animate() {
        const frame = () => {
            // Layout simulation step is handled by ForceLayout's own requestAnimationFrame loop
            // Camera smoothing/animation is handled by Camera's own requestAnimationFrame loop

            this.updateNodesAndEdges(); // Update visuals based on current positions
            this.render();              // Render the scenes

            requestAnimationFrame(frame); // Continue the main render loop
        };
        frame();
    }

    dispose() {
        // Stop animation loops
        this.camera?.dispose();
        this.layout?.stop();

        // Dispose nodes and edges
        this.nodes.forEach(node => node.dispose());
        this.edges.forEach(edge => edge.dispose());
        this.nodes.clear();
        this.edges.clear();

        // Dispose THREE resources
        this.scene?.clear();
        this.cssScene?.clear();
        this.renderGL?.dispose();
        // CSS3DRenderer doesn't have a dispose method, but remove its element
        this.renderHTML?.domElement?.remove();
        this.css3dContainer?.remove();

        // Remove event listeners
        window.removeEventListener('resize', this._onWindowResize.bind(this));
        this.ui?.dispose(); // UIManager should handle its own listener removal

        console.log("SpaceGraph disposed.");
    }
}

class Node {
    space = null;
    htmlElement = null;
    cssObject = null;
    position = new THREE.Vector3();
    size = { width: 160, height: 70 }; // Default size
    data = { contentScale: 1.0, label: '', backgroundColor: '#333344' }; // Default data
    billboard = true; // Whether the node should face the camera (simple implementation)

    static DEFAULT_SIZE = { width: 160, height: 70 };
    static MIN_SIZE = { width: 80, height: 40 };
    static CONTENT_SCALE_RANGE = { min: 0.3, max: 3.0 };

    constructor(id, position = { x: 0, y: 0, z: 0 }, data = {}) {
        this.id = id ?? Utils.generateId('node');
        this.position.set(position.x, position.y, position.z);

        // Merge provided data with defaults, ensuring size is handled correctly
        const initialWidth = data.width ?? Node.DEFAULT_SIZE.width;
        const initialHeight = data.height ?? Node.DEFAULT_SIZE.height;
        this.size = { width: initialWidth, height: initialHeight };
        this.data = { ...this.data, ...data }; // Overwrite defaults with provided data

        this.htmlElement = this._createElement();
        this.cssObject = new CSS3DObject(this.htmlElement);

        this.update(); // Set initial position/rotation
        this.setContentScale(this.data.contentScale);
        this.setBackgroundColor(this.data.backgroundColor);
    }

    _createElement() {
        const el = document.createElement('div');
        el.className = 'node-html';
        el.id = `node-html-${this.id}`;
        el.dataset.nodeId = this.id; // Link HTML element back to Node object
        el.style.width = `${this.size.width}px`;
        el.style.height = `${this.size.height}px`;
        // Prevent browser's default drag behavior on the node itself
        el.draggable = false;
        el.ondragstart = (e) => e.preventDefault();

        // Inner structure for content and controls
        el.innerHTML = `
<div class="node-inner-wrapper">
    <div class="node-content" spellcheck="false" style="transform: scale(${this.data.contentScale});">
    ${this.data.label || ''}
</div>
<div class="node-controls">
    <button class="node-quick-button node-content-zoom-in" title="Zoom In Content (+)">+</button>
    <button class="node-quick-button node-content-zoom-out" title="Zoom Out Content (-)">-</button>
    <button class="node-quick-button node-grow" title="Grow Node (Ctrl++)">➚</button>
    <button class="node-quick-button node-shrink" title="Shrink Node (Ctrl+-)">➘</button>
    <button class="node-quick-button delete-button node-delete" title="Delete Node (Del)">×</button>
</div>
</div>
<div class="resize-handle" title="Resize Node"></div>
    `;
        return el;
    }

    setPosition(x, y, z) {
        this.position.set(x, y, z);
        // No need to call update() here, it will be called in the main loop or drag handler
    }

    setSize(width, height, scaleContent = false) {
        const oldWidth = this.size.width;
        const oldHeight = this.size.height;

        this.size.width = Math.max(Node.MIN_SIZE.width, width);
        this.size.height = Math.max(Node.MIN_SIZE.height, height);

        if (this.htmlElement) {
            this.htmlElement.style.width = `${this.size.width}px`;
            this.htmlElement.style.height = `${this.size.height}px`;
        }

        // Optionally scale content proportionally to area change
        if (scaleContent && oldWidth > 0 && oldHeight > 0) {
            const scaleFactor = Math.sqrt((this.size.width * this.size.height) / (oldWidth * oldHeight));
            this.setContentScale(this.data.contentScale * scaleFactor);
        }

        this.space?.layout?.kick(); // Nudge layout if size changes
    }

    setContentScale(scale) {
        this.data.contentScale = Utils.clamp(scale, Node.CONTENT_SCALE_RANGE.min, Node.CONTENT_SCALE_RANGE.max);
        const contentEl = this.htmlElement?.querySelector('.node-content');
        if (contentEl) {
            contentEl.style.transform = `scale(${this.data.contentScale})`;
        }
    }

    setBackgroundColor(color) {
        this.data.backgroundColor = color;
        if (this.htmlElement) {
            this.htmlElement.style.setProperty('--node-bg', this.data.backgroundColor);
        }
    }

    adjustContentScale(deltaFactor) { // Takes a factor like 1.1 or 0.9
        this.setContentScale(this.data.contentScale * deltaFactor);
    }

    adjustNodeSize(factor) { // Takes a factor like 1.1 or 0.9
        this.setSize(this.size.width * factor, this.size.height * factor, false); // Content scale adjusted separately
    }

    update(space) {
        if (this.cssObject) {
            this.cssObject.position.copy(this.position);
            // Simple billboard effect (optional, can cause jitter with active layout)
            // if (space && this.billboard && space.camera) {
            //     this.cssObject.rotation.copy(space.camera.rotation);
            // }
        }
    }

    dispose() {
        this.htmlElement?.remove();
        this.cssObject?.parent?.remove(this.cssObject);
        this.htmlElement = null;
        this.cssObject = null;
        this.space = null;
    }

    // --- Interaction State Methods ---
    startDrag() {
        this.htmlElement?.classList.add('dragging');
        this.space?.layout?.fixNode(this);
    }

    drag(newPosition) {
        this.setPosition(newPosition.x, newPosition.y, newPosition.z);
    }

    endDrag() {
        this.htmlElement?.classList.remove('dragging');
        this.space?.layout?.releaseNode(this);
        this.space?.layout?.kick();
    }

    startResize() {
        this.htmlElement?.classList.add('resizing');
        this.space?.layout?.fixNode(this);
    }

    resize(newWidth, newHeight) {
        this.setSize(newWidth, newHeight);
    }

    endResize() {
        this.htmlElement?.classList.remove('resizing');
        this.space?.layout?.releaseNode(this);
        // Layout kick happens within setSize
    }
}

class NoteNode extends Node {
    constructor(id, pos = { x: 0, y: 0, z: 0 }, data = { content: '' }) {
        // Use content as the initial label for the base Node's HTML
        super(id, pos, { ...data, type: 'note', label: data.content });
        this._initRichText();
    }

    _initRichText() {
        this.htmlElement.classList.add('note-node');
        const contentDiv = this.htmlElement.querySelector('.node-content');
        if (contentDiv) {
            contentDiv.contentEditable = "true";
            contentDiv.innerHTML = this.data.label || ''; // Ensure content is set (label holds the HTML)

            let debounceTimer;
            contentDiv.addEventListener('input', () => {
                // Update the node's data after a short delay to avoid excessive updates
                clearTimeout(debounceTimer);
                debounceTimer = setTimeout(() => {
                    this.data.label = contentDiv.innerHTML; // Store the HTML content back into data.label
                }, 300);
            });

            // Prevent drag/pan operations when interacting with content
            contentDiv.addEventListener('mousedown', e => e.stopPropagation());
            contentDiv.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });

            // Allow scrolling within the content div if it overflows, prevent page scroll
            contentDiv.addEventListener('wheel', e => {
                if (contentDiv.scrollHeight > contentDiv.clientHeight || contentDiv.scrollWidth > contentDiv.clientWidth) {
                    // Only stop propagation if the content is actually scrollable
                    if ((e.deltaY < 0 && contentDiv.scrollTop > 0) ||
                        (e.deltaY > 0 && contentDiv.scrollTop < contentDiv.scrollHeight - contentDiv.clientHeight) ||
                        (e.deltaX < 0 && contentDiv.scrollLeft > 0) ||
                        (e.deltaX > 0 && contentDiv.scrollLeft < contentDiv.scrollWidth - contentDiv.clientWidth))
                    {
                        e.stopPropagation(); // Allow internal scroll
                    }
                }
                // Let wheel event bubble up for zooming if content isn't scrollable in that direction
            }, { passive: false });
        }
    }
}

class Edge {
    geo = null; // THREE.Line object
    data = { color: 0x00d0ff, thickness: 1.5, style: 'solid', constraint: 'normal' }; // Default visual/layout data

    static DEFAULT_COLOR = 0x00d0ff;
    static HIGHLIGHT_COLOR = 0x00ffff;
    static DEFAULT_OPACITY = 0.6;
    static HIGHLIGHT_OPACITY = 1.0;

    constructor(id, sourceNode, targetNode, data = {}) {
        if (!sourceNode || !targetNode) throw new Error("Edge requires valid source and target nodes.");
        this.id = id;
        this.source = sourceNode;
        this.target = targetNode;
        this.data = { ...this.data, ...data }; // Merge provided data with defaults
        this.geo = this._createLineGeometry();
        this.update(); // Set initial points
    }

    _createLineGeometry() {
        const material = new THREE.LineBasicMaterial({
            color: this.data.color ?? Edge.DEFAULT_COLOR,
            linewidth: this.data.thickness ?? 1.5, // Note: linewidth > 1 limited support
            transparent: true,
            opacity: Edge.DEFAULT_OPACITY,
            depthTest: false, // Render edges slightly "on top"
        });

        const points = [new THREE.Vector3(), new THREE.Vector3()];
        const geometry = new THREE.BufferGeometry().setFromPoints(points);

        const line = new THREE.Line(geometry, material);
        line.renderOrder = -1; // Render before nodes if depthTest were true, less critical if false
        line.userData = { edgeId: this.id }; // Link THREE object back to Edge instance

        return line;
    }

    update() {
        if (!this.geo || !this.source || !this.target) return;

        const positions = this.geo.geometry.attributes.position;
        positions.setXYZ(0, this.source.position.x, this.source.position.y, this.source.position.z);
        positions.setXYZ(1, this.target.position.x, this.target.position.y, this.target.position.z);
        positions.needsUpdate = true; // Important!

        this.geo.geometry.computeBoundingSphere();
    }

    setHighlight(highlight) {
        if (!this.geo || !this.geo.material) return;
        const material = this.geo.material;
        material.opacity = highlight ? Edge.HIGHLIGHT_OPACITY : Edge.DEFAULT_OPACITY;
        material.color.set(highlight ? Edge.HIGHLIGHT_COLOR : (this.data.color ?? Edge.DEFAULT_COLOR));
        // Note: Changing linewidth on highlight might require Line2 from examples/jsm/lines
        // material.linewidth = highlight ? (this.data.thickness ?? 1.5) * 1.5 : (this.data.thickness ?? 1.5);
        material.needsUpdate = true;
    }

    dispose() {
        if (this.geo) {
            this.geo.geometry?.dispose();
            this.geo.material?.dispose();
            this.geo.parent?.remove(this.geo);
            this.geo = null;
        }
    }
}

class UIManager {
    space = null;
    container = null;
    contextMenuElement = null;
    confirmDialogElement = null;

    // State variables
    draggedNode = null;
    resizedNode = null;
    hoveredEdge = null;
    edgeMenuObject = null; // CSS3DObject wrapper for the edge menu

    resizeStartPos = { x: 0, y: 0 };
    resizeStartSize = { width: 0, height: 0 };
    dragOffset = new THREE.Vector3();

    isPointerDown = false;
    isPrimaryDown = false;
    isSecondaryDown = false;
    potentialClick = true;
    lastPointerPos = { x: 0, y: 0 };

    confirmCallback = null; // Callback for confirmation dialog

    constructor(space, contextMenuEl, confirmDialogEl) {
        if (!space || !contextMenuEl || !confirmDialogEl) {
            throw new Error("UIManager requires SpaceGraph instance and menu/dialog elements.");
        }
        this.space = space;
        this.container = space.container;
        this.contextMenuElement = contextMenuEl;
        this.confirmDialogElement = confirmDialogEl;

        this._bindEvents();
    }

    _bindEvents() {
        this.container.addEventListener('pointerdown', this._onPointerDown);
        window.addEventListener('pointermove', this._onPointerMove); // Use window for move/up to capture outside container
        window.addEventListener('pointerup', this._onPointerUp);
        this.container.addEventListener('contextmenu', this._onContextMenu);
        document.addEventListener('click', this._onDocumentClick, true); // Use capture phase
        this.contextMenuElement.addEventListener('click', this._onContextMenuClick);
        $('#confirm-yes', this.confirmDialogElement)?.addEventListener('click', this._onConfirmYes);
        $('#confirm-no', this.confirmDialogElement)?.addEventListener('click', this._onConfirmNo);
        window.addEventListener('keydown', this._onKeyDown);
        this.container.addEventListener('wheel', this._onWheel, { passive: false }); // Need passive:false to preventDefault
    }

    // --- Event Handlers ---

    _onPointerDown = (e) => {
        this.isPointerDown = true;
        this.isPrimaryDown = e.button === 0;
        this.isSecondaryDown = e.button === 2;
        this.potentialClick = true; // Assume click until moved significantly
        this.lastPointerPos = { x: e.clientX, y: e.clientY };

        const targetInfo = this._getTargetInfo(e);

        if (this.isSecondaryDown) {
            // Context menu is handled by 'contextmenu' event.
            e.preventDefault(); // Prevent default browser context menu immediately
            return;
        }

        if (this.isPrimaryDown) {
            // Order of checks: Controls > Resize > Node Drag > Edge Select > Background Pan/Deselect
            if (this._handlePointerDownControls(e, targetInfo)) return;
            if (this._handlePointerDownResize(e, targetInfo)) return;
            if (this._handlePointerDownNode(e, targetInfo)) return;
            if (this._handlePointerDownEdge(e, targetInfo)) return;
            if (this._handlePointerDownBackground(e, targetInfo)) return;
        }

        if (e.button === 1) {
            // Autozoom on middle *up* usually feels better, handle in _onPointerUp
            e.preventDefault(); // Prevent default middle-click scroll/pan
        }
    }

    _onPointerMove = (e) => {
        if (!this.isPointerDown) {
            // Handle Edge Highlighting on Hover (only when pointer is not down)
            this._handleHover(e);
            return;
        }

        const dx = e.clientX - this.lastPointerPos.x;
        const dy = e.clientY - this.lastPointerPos.y;
        // If moved more than a few pixels, it's not a click
        if (Math.sqrt(dx * dx + dy * dy) > 3) {
            this.potentialClick = false;
        }
        this.lastPointerPos = { x: e.clientX, y: e.clientY };

        if (this.resizedNode) {
            e.preventDefault();
            const newWidth = this.resizeStartSize.width + (e.clientX - this.resizeStartPos.x);
            const newHeight = this.resizeStartSize.height + (e.clientY - this.resizeStartPos.y);
            this.resizedNode.resize(newWidth, newHeight);
            this.space.updateNodesAndEdges(); // Update edge positions during resize
            return;
        }

        if (this.draggedNode) {
            e.preventDefault();
            const worldPos = this.space.screenToWorld(e.clientX, e.clientY, this.draggedNode.position.z);
            if (worldPos) {
                this.draggedNode.drag(worldPos.sub(this.dragOffset));
                this.space.updateNodesAndEdges(); // Update edge positions during drag
            }
            return;
        }

        if (this.space.isLinking) {
            e.preventDefault(); // Prevent panning while linking
            this._updateTempLinkLine(e.clientX, e.clientY);
            // Highlight potential target node
            const targetInfo = this._getTargetInfo(e);
            $$('.node-html.linking-target').forEach(el => el.classList.remove('linking-target'));
            if (targetInfo.node && targetInfo.node !== this.space.linkSourceNode) {
                targetInfo.node.htmlElement?.classList.add('linking-target');
            }
            return;
        }

        // Handle Camera Panning (if primary button is down and not dragging/resizing/linking)
        if (this.isPrimaryDown && !this.draggedNode && !this.resizedNode && !this.space.isLinking) {
            this.space.camera?.pan(e); // Delegate panning logic to Camera class
        }
    }

    _onPointerUp = (e) => {
        this.container.style.cursor = this.space.isLinking ? 'crosshair' : 'grab'; // Reset cursor

        if (this.resizedNode) {
            this.resizedNode.endResize();
            this.resizedNode = null;
        }
        else if (this.draggedNode) {
            this.draggedNode.endDrag();
            this.draggedNode = null;
        }
        else if (this.space.isLinking && e.button === 0) { // Only complete link on primary button up
            this._completeLinking(e);
        }
        else if (e.button === 1 && this.potentialClick) { // Middle button click
            const { node } = this._getTargetInfo(e);
            if (node) {
                this.space.autoZoom(node);
                e.preventDefault();
            }
        }
        // Right click up: No action needed here, contextmenu event handles it.
        // Primary click up: Selection handled in pointerdown, deselection in _onDocumentClick.

        this.space.camera?.endPan();

        // Reset State
        this.isPointerDown = false;
        this.isPrimaryDown = false;
        this.isSecondaryDown = false;
        this.potentialClick = false;
        $$('.node-html.linking-target').forEach(el => el.classList.remove('linking-target'));
    }

    _onContextMenu = (e) => {
        e.preventDefault();
        this._hideContextMenu();

        const targetInfo = this._getTargetInfo(e);
        let menuItems = [];

        if (targetInfo.node) {
            if (this.space.nodeSelected !== targetInfo.node) {
                this.space.setSelectedNode(targetInfo.node);
            }
            menuItems = this._getContextMenuItemsNode(targetInfo.node);
        } else if (targetInfo.intersectedEdge) {
            if (this.space.edgeSelected !== targetInfo.intersectedEdge) {
                this.space.setSelectedEdge(targetInfo.intersectedEdge);
            }
            menuItems = this._getContextMenuItemsEdge(targetInfo.intersectedEdge);
        } else {
            this.space.setSelectedNode(null);
            this.space.setSelectedEdge(null);
            const worldPos = this.space.screenToWorld(e.clientX, e.clientY, 0); // Get world position at z=0
            menuItems = this._getContextMenuItemsBackground(worldPos);
        }

        if (menuItems.length > 0) {
            this._showContextMenu(e.clientX, e.clientY, menuItems);
        }
    }

    _onDocumentClick = (e) => {
        // Hide context menu if clicking outside it
        if (!this.contextMenuElement.contains(e.target)) {
            this._hideContextMenu();
        }

        // Hide edge menu if clicking outside it AND not clicking the selected edge itself
        if (this.edgeMenuObject && this.edgeMenuObject.element && !this.edgeMenuObject.element.contains(e.target)) {
            const edgeObj = this.space.intersectedObject(e.clientX, e.clientY);
            // Only hide if the click wasn't on the currently selected edge (which would re-select it)
            if (this.space.edgeSelected !== edgeObj) {
                this.space.setSelectedEdge(null); // Deselecting the edge hides the menu via the setter
            }
        }

        // Deselect node/edge if clicking on the background
        const targetInfo = this._getTargetInfo(e);
        if (!targetInfo.nodeElement && !targetInfo.intersectedEdge &&
            !this.contextMenuElement.contains(e.target) &&
            !(this.edgeMenuObject && this.edgeMenuObject.element.contains(e.target)) &&
            !this.confirmDialogElement.contains(e.target)
        )
        {
            // Deselect if nothing specific was clicked and it wasn't a pan action
            if (this.potentialClick && !this.space.camera?.isPanning) {
                this.space.setSelectedNode(null);
                this.space.setSelectedEdge(null);
            }
        }
    }

    _onContextMenuClick = (e) => {
        const li = e.target.closest('li[data-action]');
        if (!li) return;

        const action = li.dataset.action;
        const nodeId = li.dataset.nodeId;
        const edgeId = li.dataset.edgeId;
        const positionData = li.dataset.position;

        this._hideContextMenu(); // Hide menu after action selected

        switch (action) {
            case 'edit-node': {
                const node = this.space.getNodeById(nodeId);
                const contentDiv = node?.htmlElement?.querySelector('.node-content');
                if (contentDiv?.contentEditable === "true") {
                    contentDiv.focus();
                    // Move cursor to end (optional)
                    const range = document.createRange();
                    const sel = window.getSelection();
                    range.selectNodeContents(contentDiv);
                    range.collapse(false);
                    sel?.removeAllRanges();
                    sel?.addRange(range);
                }
                break;
            }
            case 'delete-node':
                this._showConfirm(`Delete node "${nodeId?.substring(0, 10)}..."?`, () => {
                    this.space.removeNode(nodeId);
                });
                break;
            case 'delete-edge':
                this._showConfirm(`Delete edge "${edgeId?.substring(0, 10)}..."?`, () => {
                    this.space.removeEdge(edgeId);
                });
                break;
            case 'autozoom-node': {
                const node = this.space.getNodeById(nodeId);
                if (node) this.space.autoZoom(node);
                break;
            }
            case 'create-note': {
                if (!positionData) break;
                try {
                    const position = JSON.parse(positionData);
                    const newNode = this.space.addNode(new NoteNode(null, position, { content: 'New Note ✨' }));
                    this.space.layout?.kick(); // Nudge layout
                    // Select and focus after a short delay to allow rendering
                    setTimeout(() => {
                        this.space.focusOnNode(newNode, 0.6, true); // Push history for new node focus
                        this.space.setSelectedNode(newNode); // Select the new node
                        newNode.htmlElement?.querySelector('.node-content')?.focus();
                    }, 100);
                } catch (err) {
                    console.error("Failed to parse position data for new node:", err);
                }
                break;
            }
            case 'center-view':
                this.space.centerView();
                break;
            case 'reset-view':
                this.space.camera?.resetView();
                break;
            case 'start-link': {
                const sourceNode = this.space.getNodeById(nodeId);
                if (sourceNode) this._startLinking(sourceNode);
                break;
            }
            case 'reverse-edge': {
                const edge = this.space.getEdgeById(edgeId);
                if (edge) {
                    const oldSource = edge.source;
                    edge.source = edge.target;
                    edge.target = oldSource;
                    edge.update();
                    this.space.layout?.kick(); // Nudge layout
                }
                break;
            }
            case 'edit-edge': {
                // Ensure the edge menu is shown for the selected edge
                const edge = this.space.getEdgeById(edgeId);
                if (edge) {
                    // Selection might have been lost when context menu closed, re-select
                    this.space.setSelectedEdge(edge);
                    // showEdgeMenu is called automatically by setSelectedEdge
                } else {
                    console.warn("Edit Edge Style: Edge not found", edgeId);
                }
                break;
            }
            default:
                console.warn("Unknown context menu action:", action);
        }
    }

    _onConfirmYes = () => {
        this.confirmCallback?.();
        this._hideConfirm();
    }

    _onConfirmNo = () => {
        this._hideConfirm();
    }

    _onKeyDown = (e) => {
        // Ignore shortcuts if focus is inside an input, textarea, or contentEditable element
        const activeEl = document.activeElement;
        if (activeEl && (activeEl.tagName === 'INPUT' || activeEl.tagName === 'TEXTAREA' || activeEl.isContentEditable)) {
            // Allow Escape key to cancel linking even when editing
            if (e.key === 'Escape' && this.space.isLinking) {
                e.preventDefault();
                this.cancelLinking();
            }
            return;
        }

        const selectedNode = this.space.nodeSelected;
        const selectedEdge = this.space.edgeSelected;

        switch (e.key) {
            case 'Delete':
            case 'Backspace':
                if (selectedNode) {
                    e.preventDefault();
                    this._showConfirm(`Delete node "${selectedNode.id.substring(0, 10)}..."?`, () => this.space.removeNode(selectedNode.id));
                } else if (selectedEdge) {
                    e.preventDefault();
                    this._showConfirm(`Delete edge "${selectedEdge.id.substring(0, 10)}..."?`, () => this.space.removeEdge(selectedEdge.id));
                }
                break;

            case 'Escape':
                e.preventDefault();
                if (this.space.isLinking) {
                    this.cancelLinking();
                } else if (this.contextMenuElement.style.display === 'block') {
                    this._hideContextMenu();
                } else if (this.confirmDialogElement.style.display === 'block') {
                    this._hideConfirm();
                } else if (this.edgeMenuObject) {
                    this.space.setSelectedEdge(null); // Deselect edge (hides menu)
                } else if (selectedNode || selectedEdge) {
                    this.space.setSelectedNode(null);
                    this.space.setSelectedEdge(null);
                }
                break;

            case 'Enter':
                if (selectedNode instanceof NoteNode) {
                    e.preventDefault();
                    selectedNode.htmlElement?.querySelector('.node-content')?.focus();
                }
                break;

            case '+':
            case '=': // Plus key (often shared with equals)
                if (selectedNode) {
                    e.preventDefault();
                    if (e.ctrlKey || e.metaKey) { // Ctrl/Cmd + + : Grow Node
                        selectedNode.adjustNodeSize(1.2);
                    } else { // Just + : Zoom Content In
                        selectedNode.adjustContentScale(1.15);
                    }
                }
                break;

            case '-':
            case '_': // Minus key (often shared with underscore)
                if (selectedNode) {
                    e.preventDefault();
                    if (e.ctrlKey || e.metaKey) { // Ctrl/Cmd + - : Shrink Node
                        selectedNode.adjustNodeSize(1 / 1.2);
                    } else { // Just - : Zoom Content Out
                        selectedNode.adjustContentScale(1 / 1.15);
                    }
                }
                break;

            case ' ': // Spacebar - Recenter on selection or overall view
                e.preventDefault(); // Prevent page scroll
                if (selectedNode) {
                    this.space.focusOnNode(selectedNode, 0.5, true); // Push history
                } else if (selectedEdge) {
                    // Focus on midpoint of edge
                    const midPoint = new THREE.Vector3().lerpVectors(selectedEdge.source.position, selectedEdge.target.position, 0.5);
                    const dist = selectedEdge.source.position.distanceTo(selectedEdge.target.position);
                    this.space.camera?.pushState();
                    this.space.camera?.moveTo(midPoint.x, midPoint.y, midPoint.z + dist * 0.6 + 100, 0.5, midPoint);
                } else {
                    this.space.centerView(); // Center overall view
                }
                break;
        }
    }

    _onWheel = (e) => {
        const targetInfo = this._getTargetInfo(e);

        // Allow wheel scroll inside specific elements (contentEditable, controls)
        if (targetInfo.contentEditable || e.target.closest('.node-controls')) {
            // Let the browser handle scrolling within these elements
            // The NoteNode wheel listener handles stopping propagation if scrollable
            return;
        }

        // Ctrl/Meta + Wheel: Adjust Node Content Scale
        if (e.ctrlKey || e.metaKey) {
            if (targetInfo.node) {
                e.preventDefault(); // Prevent browser zoom
                e.stopPropagation(); // Prevent camera zoom
                const scaleDeltaFactor = e.deltaY < 0 ? 1.1 : (1 / 1.1); // Zoom in or out
                targetInfo.node.adjustContentScale(scaleDeltaFactor);
            }
            // If Ctrl+Wheel is not over a node, let the browser zoom (default behavior)
        }
        // Normal Wheel: Camera Zoom
        else {
            e.preventDefault(); // Prevent default page scroll
            this.space.camera?.zoom(e); // Delegate zoom logic to Camera class
        }
    }

    // --- Helper Methods ---

    _getTargetInfo(event) {
        const element = document.elementFromPoint(event.clientX, event.clientY);
        if (!element) return { element: null };

        // Check closest relevant ancestor elements
        const nodeElement = element.closest('.node-html');
        const resizeHandle = element.closest('.resize-handle');
        const nodeControls = element.closest('.node-controls button');
        const contentEditable = element.closest('.node-content[contenteditable="true"]');
        const interactiveElement = element.closest('.node-content button, .node-content input, .node-content textarea, .node-content select, .node-content a');

        const node = nodeElement ? this.space.getNodeById(nodeElement.dataset.nodeId) : null;

        // Check for edge intersection only if not interacting with a node element or its parts
        const intersectedEdge = (!nodeElement) ? this.space.intersectedObject(event.clientX, event.clientY) : null;

        return {
            element,
            nodeElement,
            resizeHandle,
            nodeControls,
            contentEditable,
            interactiveElement,
            node,
            intersectedEdge
        };
    }

    _handleHover(e) {
        // Only update hover if not currently interacting
        if (this.isPointerDown || this.draggedNode || this.resizedNode || this.space.isLinking) {
            // Clear hover if interaction starts
            if (this.hoveredEdge && this.hoveredEdge !== this.space.edgeSelected) {
                this.hoveredEdge.setHighlight(false);
                this.hoveredEdge = null;
            }
            return;
        }

        const { intersectedEdge } = this._getTargetInfo(e);

        if (this.hoveredEdge !== intersectedEdge) {
            // De-highlight previous edge (if it's not the selected one)
            if (this.hoveredEdge && this.hoveredEdge !== this.space.edgeSelected) {
                this.hoveredEdge.setHighlight(false);
            }

            this.hoveredEdge = intersectedEdge;

            // Highlight new edge (if it's not the selected one)
            if (this.hoveredEdge && this.hoveredEdge !== this.space.edgeSelected) {
                this.hoveredEdge.setHighlight(true);
            }
        }
    }

    // --- Pointer Down Sub-Handlers ---

    _handlePointerDownControls(e, targetInfo) {
        if (targetInfo.nodeControls && targetInfo.node) {
            e.preventDefault(); // Prevent drag/pan
            e.stopPropagation(); // Prevent triggering node selection/drag
            const button = targetInfo.nodeControls;
            const action = button.classList.contains('node-delete') ? 'delete' :
                button.classList.contains('node-content-zoom-in') ? 'zoom-in' :
                    button.classList.contains('node-content-zoom-out') ? 'zoom-out' :
                        button.classList.contains('node-grow') ? 'grow' :
                            button.classList.contains('node-shrink') ? 'shrink' : null;

            switch (action) {
                case 'delete':
                    this._showConfirm(`Delete node "${targetInfo.node.id.substring(0, 10)}..."?`, () => this.space.removeNode(targetInfo.node.id));
                    break;
                case 'zoom-in': targetInfo.node.adjustContentScale(1.15); break;
                case 'zoom-out': targetInfo.node.adjustContentScale(1 / 1.15); break;
                case 'grow': targetInfo.node.adjustNodeSize(1.2); break;
                case 'shrink': targetInfo.node.adjustNodeSize(1 / 1.2); break;
            }
            this._hideContextMenu();
            return true; // Indicate handled
        }
        return false;
    }

    _handlePointerDownResize(e, targetInfo) {
        if (targetInfo.resizeHandle && targetInfo.node) {
            e.preventDefault();
            e.stopPropagation();
            this.resizedNode = targetInfo.node;
            this.resizedNode.startResize();
            this.resizeStartPos = { x: e.clientX, y: e.clientY };
            this.resizeStartSize = { ...this.resizedNode.size }; // Copy size
            this.container.style.cursor = 'nwse-resize';
            this._hideContextMenu();
            return true; // Indicate handled
        }
        return false;
    }

    _handlePointerDownNode(e, targetInfo) {
        // Allow drag only if clicking directly on node background,
        // not on controls, resize handle, interactive elements, or editable content.
        if (targetInfo.nodeElement && targetInfo.node &&
            !targetInfo.nodeControls && !targetInfo.resizeHandle &&
            !targetInfo.interactiveElement && !targetInfo.contentEditable)
        {
            e.preventDefault(); // Prevent text selection, default browser drag
            this.draggedNode = targetInfo.node;
            this.draggedNode.startDrag();

            // Calculate offset from node center in world space
            const worldPos = this.space.screenToWorld(e.clientX, e.clientY, this.draggedNode.position.z);
            this.dragOffset = worldPos ? worldPos.sub(this.draggedNode.position) : new THREE.Vector3();

            this.container.style.cursor = 'grabbing';
            this.space.setSelectedNode(targetInfo.node); // Select node on drag start
            this._hideContextMenu();
            return true; // Indicate handled
        }

        // If clicking interactive or editable content, select the node but don't start drag/pan
        if (targetInfo.nodeElement && targetInfo.node && (targetInfo.interactiveElement || targetInfo.contentEditable)) {
            e.stopPropagation(); // Prevent background panning
            // Select the node if it's not already selected
            if (this.space.nodeSelected !== targetInfo.node) {
                this.space.setSelectedNode(targetInfo.node);
            }
            this._hideContextMenu();
            // Don't return true, allow default behavior for the interactive element (e.g., button click, text selection)
        }
        return false;
    }

    _handlePointerDownEdge(e, targetInfo) {
        if (targetInfo.intersectedEdge && !targetInfo.nodeElement) { // Prioritize nodes if overlapping
            e.preventDefault(); // Prevent panning
            this.space.setSelectedEdge(targetInfo.intersectedEdge);
            this._hideContextMenu();
            return true; // Indicate handled
        }
        return false;
    }

    _handlePointerDownBackground(e, targetInfo) {
        // Clicked on background (no node, no edge)
        if (!targetInfo.nodeElement && !targetInfo.intersectedEdge) {
            this._hideContextMenu();
            // Deselection happens on click up/document click to differentiate from drag start
            this.space.camera?.startPan(e); // Start panning
            // Don't return true, panning might be ongoing
        }
        return false;
    }

    // --- Context Menu Item Generators ---

    _getContextMenuItemsNode(node) {
        const items = [];
        if (node instanceof NoteNode) {
            items.push({ label: "Edit Content 📝", action: "edit-node", nodeId: node.id });
        }
        items.push({ label: "Start Link ✨", action: "start-link", nodeId: node.id });
        items.push({ label: "Auto Zoom / Back 🖱️", action: "autozoom-node", nodeId: node.id });
        items.push({ type: 'separator' });
        items.push({ label: "Delete Node 🗑️", action: "delete-node", nodeId: node.id });
        return items;
    }

    _getContextMenuItemsEdge(edge) {
        return [
            { label: "Edit Style...", action: "edit-edge", edgeId: edge.id }, // Opens the persistent edge menu
            { label: "Reverse Direction", action: "reverse-edge", edgeId: edge.id },
            { type: 'separator' },
            { label: "Delete Edge 🗑️", action: "delete-edge", edgeId: edge.id },
        ];
    }

    _getContextMenuItemsBackground(worldPos) {
        const items = [];
        if (worldPos) {
            items.push({
                label: "Create Note Here ➕",
                action: "create-note",
                position: JSON.stringify({ x: worldPos.x, y: worldPos.y, z: worldPos.z }) // Stringify position for dataset attribute
            });
        }
        items.push({ label: "Center View 🧭", action: "center-view" });
        items.push({ label: "Reset Zoom & Pan", action: "reset-view" });
        return items;
    }

    // --- Context Menu Display ---

    _showContextMenu(x, y, items) {
        const cm = this.contextMenuElement;
        cm.innerHTML = '';
        const ul = document.createElement('ul');

        items.forEach(itemData => {
            const li = document.createElement('li');
            if (itemData.type === 'separator') {
                li.className = 'separator';
            } else {
                li.textContent = itemData.label;
                // Add data attributes for action and parameters
                Object.entries(itemData).forEach(([key, value]) => {
                    if (key !== 'label' && key !== 'type' && value !== undefined && value !== null) {
                        li.dataset[key] = value;
                    }
                });
                if (itemData.disabled) {
                    li.classList.add('disabled');
                }
            }
            ul.appendChild(li);
        });
        cm.appendChild(ul);

        // Position the menu, ensuring it stays within viewport bounds
        const menuWidth = cm.offsetWidth;
        const menuHeight = cm.offsetHeight;
        const margin = 5;
        let finalX = x + margin;
        let finalY = y + margin;

        if (finalX + menuWidth > window.innerWidth - margin) {
            finalX = x - menuWidth - margin;
        }
        if (finalY + menuHeight > window.innerHeight - margin) {
            finalY = y - menuHeight - margin;
        }
        finalX = Math.max(margin, finalX);
        finalY = Math.max(margin, finalY);

        cm.style.left = `${finalX}px`;
        cm.style.top = `${finalY}px`;
        cm.style.display = 'block';
    }

    _hideContextMenu = () => {
        this.contextMenuElement.style.display = 'none';
        this.contextMenuElement.innerHTML = '';
    }

    // --- Confirmation Dialog ---

    _showConfirm(message, onConfirm) {
        const messageEl = $('#confirm-message', this.confirmDialogElement);
        if (messageEl) messageEl.textContent = message;
        this.confirmCallback = onConfirm;
        this.confirmDialogElement.style.display = 'block';
    }

    _hideConfirm = () => {
        this.confirmDialogElement.style.display = 'none';
        this.confirmCallback = null;
    }

    // --- Linking ---

    _startLinking(sourceNode) {
        if (!sourceNode || this.space.isLinking) return;
        this.space.isLinking = true;
        this.space.linkSourceNode = sourceNode;
        this.container.style.cursor = 'crosshair';
        this._createTempLinkLine(sourceNode);
        this._hideContextMenu();
    }

    _createTempLinkLine(sourceNode) {
        this._removeTempLinkLine(); // Ensure previous line is removed
        const material = new THREE.LineDashedMaterial({
            color: 0xffaa00,
            linewidth: 2,
            dashSize: 8,
            gapSize: 4,
            transparent: true,
            opacity: 0.9,
            depthTest: false,
        });
        // Start and end points initially at source node position
        const points = [sourceNode.position.clone(), sourceNode.position.clone()];
        const geometry = new THREE.BufferGeometry().setFromPoints(points);
        this.space.tempLinkLine = new THREE.Line(geometry, material);
        this.space.tempLinkLine.computeLineDistances(); // Required for dashed lines
        this.space.tempLinkLine.renderOrder = 1; // Render on top
        this.space.scene.add(this.space.tempLinkLine);
    }

    _updateTempLinkLine(screenX, screenY) {
        if (!this.space.tempLinkLine || !this.space.linkSourceNode) return;
        // Project mouse position onto the Z-plane of the source node
        const targetPos = this.space.screenToWorld(screenX, screenY, this.space.linkSourceNode.position.z);
        if (targetPos) {
            const positions = this.space.tempLinkLine.geometry.attributes.position;
            positions.setXYZ(1, targetPos.x, targetPos.y, targetPos.z); // Update end point
            positions.needsUpdate = true;
            this.space.tempLinkLine.geometry.computeBoundingSphere();
            this.space.tempLinkLine.computeLineDistances(); // Recompute for dashes
        }
    }

    _removeTempLinkLine() {
        if (this.space.tempLinkLine) {
            this.space.tempLinkLine.geometry?.dispose();
            this.space.tempLinkLine.material?.dispose();
            this.space.scene.remove(this.space.tempLinkLine);
            this.space.tempLinkLine = null;
        }
    }

    _completeLinking(event) {
        this._removeTempLinkLine();
        const targetInfo = this._getTargetInfo(event);
        const targetNode = targetInfo.node;

        // Check if target is valid (exists and is not the source node)
        if (targetNode && targetNode !== this.space.linkSourceNode) {
            this.space.addEdge(this.space.linkSourceNode, targetNode);
        }

        this.cancelLinking(); // Reset linking state regardless of success
    }

    cancelLinking = () => { // Make publically callable e.g. via Escape key
        this._removeTempLinkLine();
        this.space.isLinking = false;
        this.space.linkSourceNode = null;
        this.container.style.cursor = 'grab'; // Reset cursor
        $$('.node-html.linking-target').forEach(el => el.classList.remove('linking-target'));
    }

    // --- Edge Menu ---

    showEdgeMenu(edge) {
        if (!edge || this.edgeMenuObject) return; // Don't show if no edge or menu already exists

        const menuElement = this._createEdgeMenuElement(edge);
        this.edgeMenuObject = new CSS3DObject(menuElement);
        this.space.cssScene.add(this.edgeMenuObject);
        this.updateEdgeMenuPosition();
    }

    _createEdgeMenuElement(edge) {
        const menu = document.createElement('div');
        menu.className = 'edge-menu-frame';
        menu.dataset.edgeId = edge.id; // Link menu element to edge ID
        menu.innerHTML = `
<button title="Color (NYI)" data-action="color">🎨</button>
<button title="Thickness (NYI)" data-action="thickness">➖</button>
<button title="Style (NYI)" data-action="style">〰️</button>
<button title="Constraint (NYI)" data-action="constraint">🔗</button>
<button title="Delete Edge" class="delete" data-action="delete">×</button>
    `;

        // Add event listener for button clicks within this menu
        menu.addEventListener('click', (e) => {
            const button = e.target.closest('button[data-action]');
            if (!button) return;
            const action = button.dataset.action;
            e.stopPropagation(); // Prevent click from bubbling to document/deselecting edge

            switch (action) {
                case 'delete':
                    const edgeId = menu.dataset.edgeId;
                    this._showConfirm(`Delete edge "${edgeId?.substring(0, 10)}..."?`, () => {
                        this.space.removeEdge(edgeId);
                        // No need to hide menu explicitly, removeEdge -> setSelectedEdge(null) handles it
                    });
                    break;
                // Add cases for other actions (color, thickness, style, constraint) here
                default:
                    console.warn(`Edge menu action '${action}' not implemented.`);
            }
        });
        return menu;
    }

    hideEdgeMenu = () => { // Make publically callable if needed elsewhere
        if (this.edgeMenuObject) {
            this.edgeMenuObject.element?.remove();
            this.edgeMenuObject.parent?.remove(this.edgeMenuObject);
            this.edgeMenuObject = null;
        }
    }

    updateEdgeMenuPosition = () => { // Make publically callable
        if (!this.edgeMenuObject || !this.space.edgeSelected) return;

        const edge = this.space.edgeSelected;
        // Position menu at the midpoint of the edge
        const midPoint = new THREE.Vector3().lerpVectors(edge.source.position, edge.target.position, 0.5);
        this.edgeMenuObject.position.copy(midPoint);

        // Optional: Make menu face camera (simple billboard)
        // if (this.space.camera) {
        //     this.edgeMenuObject.rotation.copy(this.space.camera.rotation);
        // }
    }

    dispose() {
        // Remove event listeners
        this.container.removeEventListener('pointerdown', this._onPointerDown);
        window.removeEventListener('pointermove', this._onPointerMove);
        window.removeEventListener('pointerup', this._onPointerUp);
        this.container.removeEventListener('contextmenu', this._onContextMenu);
        document.removeEventListener('click', this._onDocumentClick, true);
        this.contextMenuElement.removeEventListener('click', this._onContextMenuClick);
        $('#confirm-yes', this.confirmDialogElement)?.removeEventListener('click', this._onConfirmYes);
        $('#confirm-no', this.confirmDialogElement)?.removeEventListener('click', this._onConfirmNo);
        window.removeEventListener('keydown', this._onKeyDown);
        this.container.removeEventListener('wheel', this._onWheel);

        this.hideEdgeMenu();

        // Clear references
        this.space = null;
        this.container = null;
        this.contextMenuElement = null;
        this.confirmDialogElement = null;
        this.draggedNode = null;
        this.resizedNode = null;
        this.hoveredEdge = null;
        this.confirmCallback = null;

        console.log("UIManager disposed.");
    }
}

class Camera {
    space = null;
    camera = null; // The THREE.PerspectiveCamera instance
    domElement = null;

    // State
    isPanning = false;
    panStart = new THREE.Vector2();
    targetPosition = new THREE.Vector3();
    targetLookAt = new THREE.Vector3();
    currentLookAt = new THREE.Vector3();
    viewHistory = []; // Stack for storing previous view states {position, lookAt, targetNodeId}
    currentTargetNodeId = null;
    initialState = null; // Store initial state {position, lookAt} after first positioning

    // Settings
    zoomSpeed = 1.0;
    panSpeed = 0.8;
    minZoomDistance = 20;
    maxZoomDistance = 15000;
    dampingFactor = 0.12; // Smoothing factor for lerp (lower = smoother, slower)
    maxHistory = 20;

    animationFrameId = null;

    constructor(space) {
        if (!space || !space._cam || !space.container) {
            throw new Error("Camera requires a SpaceGraph instance with a camera and container.");
        }
        this.space = space;
        this.camera = space._cam;
        this.domElement = space.container;

        this.targetPosition.copy(this.camera.position);
        this.targetLookAt.set(this.camera.position.x, this.camera.position.y, 0); // Look at Z=0 plane initially
        this.currentLookAt.copy(this.targetLookAt);

        this._startUpdateLoop();
    }

    // Called once after initial centering/focusing
    setInitialState() {
        if (!this.initialState) {
            this.initialState = {
                position: this.targetPosition.clone(), // Use the target, not the potentially lagging camera position
                lookAt: this.targetLookAt.clone()
            };
        }
    }

    // --- Camera Control Methods ---

    startPan(event) {
        if (event.button !== 0 || this.isPanning) return; // Only primary button pan, prevent re-entry
        this.isPanning = true;
        this.panStart.set(event.clientX, event.clientY);
        this.domElement.classList.add('panning');
        // Stop any ongoing animations when user starts panning
        gsap.killTweensOf(this.targetPosition);
        gsap.killTweensOf(this.targetLookAt);
        this.currentTargetNodeId = null; // User interaction cancels autozoom target
    }

    pan(event) {
        if (!this.isPanning) return;

        const deltaX = event.clientX - this.panStart.x;
        const deltaY = event.clientY - this.panStart.y;

        // Calculate pan distance based on camera distance and FOV
        const cameraDist = this.camera.position.distanceTo(this.currentLookAt);
        const vFOV = this.camera.fov * Utils.DEG2RAD;
        const viewHeight = this.domElement.clientHeight || window.innerHeight;
        // Calculate the height of the view plane at the lookAt distance
        const visibleHeight = 2 * Math.tan(vFOV / 2) * Math.max(1, cameraDist); // Avoid dist=0 issues

        // Calculate pan amounts in world units
        const panX = -(deltaX / viewHeight) * visibleHeight * this.panSpeed;
        const panY = (deltaY / viewHeight) * visibleHeight * this.panSpeed;

        // Get camera's local right and up vectors in world space
        const right = new THREE.Vector3().setFromMatrixColumn(this.camera.matrixWorld, 0);
        const up = new THREE.Vector3().setFromMatrixColumn(this.camera.matrixWorld, 1);

        // Calculate the pan offset vector
        const panOffset = right.multiplyScalar(panX).add(up.multiplyScalar(panY));

        // Apply the offset to both target position and lookAt point
        this.targetPosition.add(panOffset);
        this.targetLookAt.add(panOffset);

        this.panStart.set(event.clientX, event.clientY);
    }

    endPan() {
        if (this.isPanning) {
            this.isPanning = false;
            this.domElement.classList.remove('panning');
        }
    }

    zoom(event) {
        // Stop any ongoing animations when user starts zooming
        gsap.killTweensOf(this.targetPosition);
        gsap.killTweensOf(this.targetLookAt);
        this.currentTargetNodeId = null; // User interaction cancels autozoom target

        const delta = event.deltaY;
        const zoomFactor = Math.pow(0.95, delta * 0.05 * this.zoomSpeed); // Exponential zoom factor

        // Calculate the vector from the lookAt point to the current camera position
        const lookAtToCam = new THREE.Vector3().subVectors(this.targetPosition, this.targetLookAt);
        let currentDist = lookAtToCam.length();
        let newDist = currentDist * zoomFactor;

        newDist = Utils.clamp(newDist, this.minZoomDistance, this.maxZoomDistance);

        // Set the new camera position along the lookAtToCam vector
        this.targetPosition.copy(this.targetLookAt).addScaledVector(lookAtToCam.normalize(), newDist);

        // Optional: Zoom towards mouse pointer (more complex)
        // const mouseWorldPos = this._getLookAtPlaneIntersection(event.clientX, event.clientY);
        // if (mouseWorldPos) { ... }
    }

    moveTo(x, y, z, duration = 0.7, lookAtTarget = null) {
        this.setInitialState(); // Ensure initial state is set before first move

        const targetPos = new THREE.Vector3(x, y, z);
        // If lookAtTarget is not provided, use the XY coordinates of the target position, keeping current lookAt Z
        const targetLook = lookAtTarget instanceof THREE.Vector3
            ? lookAtTarget.clone()
            : new THREE.Vector3(x, y, this.targetLookAt.z);

        // Use GSAP for smooth animation
        gsap.killTweensOf(this.targetPosition);
        gsap.killTweensOf(this.targetLookAt);

        gsap.to(this.targetPosition, {
            x: targetPos.x,
            y: targetPos.y,
            z: targetPos.z,
            duration: duration,
            ease: "power3.out",
            overwrite: true
        });
        gsap.to(this.targetLookAt, {
            x: targetLook.x,
            y: targetLook.y,
            z: targetLook.z,
            duration: duration,
            ease: "power3.out",
            overwrite: true
        });
    }

    resetView(duration = 0.7) {
        if (this.initialState) {
            this.moveTo(this.initialState.position.x, this.initialState.position.y, this.initialState.position.z, duration, this.initialState.lookAt);
        } else {
            console.warn("Camera initial state not set, resetting to default.");
            this.moveTo(0, 0, 700, duration, new THREE.Vector3(0, 0, 0));
        }
        this.viewHistory = [];
        this.currentTargetNodeId = null;
    }

    // --- View History Management ---

    pushState() {
        if (this.viewHistory.length >= this.maxHistory) {
            this.viewHistory.shift(); // Remove the oldest state
        }
        // Push the *current target* state, not the camera's potentially lagging position
        this.viewHistory.push({
            position: this.targetPosition.clone(),
            lookAt: this.targetLookAt.clone(),
            targetNodeId: this.currentTargetNodeId
        });
    }

    popState(duration = 0.6) {
        if (this.viewHistory.length > 0) {
            const prevState = this.viewHistory.pop();
            this.moveTo(prevState.position.x, prevState.position.y, prevState.position.z, duration, prevState.lookAt);
            this.currentTargetNodeId = prevState.targetNodeId; // Restore previous target node ID
        } else {
            // If history is empty, go back to the initial state
            this.resetView(duration);
        }
    }

    getCurrentTargetNodeId = () => this.currentTargetNodeId;
    setCurrentTargetNodeId = (nodeId) => {
        this.currentTargetNodeId = nodeId;
    };

    // --- Internal Update Loop ---

    _startUpdateLoop = () => { // Use arrow function to bind 'this'
        const deltaPos = this.targetPosition.distanceTo(this.camera.position);
        const deltaLookAt = this.targetLookAt.distanceTo(this.currentLookAt);
        const epsilon = 0.01; // Small threshold to stop lerping

        // Only interpolate if moving significantly, panning, or animating
        if (deltaPos > epsilon || deltaLookAt > epsilon || this.isPanning || gsap.isTweening(this.targetPosition) || gsap.isTweening(this.targetLookAt)) {
            this.camera.position.lerp(this.targetPosition, this.dampingFactor);
            this.currentLookAt.lerp(this.targetLookAt, this.dampingFactor);
            this.camera.lookAt(this.currentLookAt);
            this.camera.updateProjectionMatrix(); // Needed if FOV/aspect changes, but good practice here too
        } else {
            // Snap to final position/lookAt if close enough and not animating/panning
            if (deltaPos > 0 || deltaLookAt > 0) { // Avoid unnecessary updates if already there
                this.camera.position.copy(this.targetPosition);
                this.currentLookAt.copy(this.targetLookAt);
                this.camera.lookAt(this.currentLookAt);
                this.camera.updateProjectionMatrix();
            }
        }

        this.animationFrameId = requestAnimationFrame(this._startUpdateLoop);
    }

    // --- Utility ---

    _getLookAtPlaneIntersection(screenX, screenY) {
        const vec = new THREE.Vector2((screenX / window.innerWidth) * 2 - 1, -(screenY / window.innerHeight) * 2 + 1);
        const raycaster = new THREE.Raycaster();
        raycaster.setFromCamera(vec, this.camera);

        // Create a plane perpendicular to the camera's view direction, passing through the targetLookAt point
        const camDir = new THREE.Vector3();
        this.camera.getWorldDirection(camDir);
        const planeNormal = camDir.negate(); // Normal faces towards the camera
        const lookAtPlane = new THREE.Plane().setFromNormalAndCoplanarPoint(planeNormal, this.targetLookAt);

        const intersectPoint = new THREE.Vector3();
        return raycaster.ray.intersectPlane(lookAtPlane, intersectPoint) ? intersectPoint : null;
    }

    // --- Cleanup ---

    dispose() {
        if (this.animationFrameId) cancelAnimationFrame(this.animationFrameId);
        this.animationFrameId = null;

        gsap.killTweensOf(this.targetPosition);
        gsap.killTweensOf(this.targetLookAt);

        this.space = null;
        this.camera = null;
        this.domElement = null;
        this.viewHistory = [];

        console.log("Camera disposed.");
    }
}

class ForceLayout {
    space = null;
    nodes = []; // Array of Node objects managed by the layout
    edges = []; // Array of Edge objects managed by the layout
    velocities = new Map(); // Map<nodeId, THREE.Vector3>
    fixedNodes = new Set(); // Set<Node> of nodes whose positions are fixed

    // State
    isRunning = false;
    animationFrameId = null;
    totalEnergy = Infinity;
    lastKickTime = 0;
    autoStopTimeout = null;

    // Settings
    settings = {
        repulsion: 3000,        // Base repulsion force strength
        attraction: 0.001,       // Base attraction force (spring constant)
        idealEdgeLength: 200,   // Target length for edges
        centerStrength: 0.0005,  // Strength of gravity pulling towards the center
        damping: 0.92,          // Velocity damping factor per step (closer to 1 = less damping)
        minEnergyThreshold: 0.1,// Energy level below which simulation might stop
        gravityCenter: new THREE.Vector3(0, 0, 0), // Point towards which gravity pulls
        zSpreadFactor: 0.15,     // Multiplier for Z-axis forces (less influence than XY)
        autoStopDelay: 4000,    // Milliseconds of low energy before stopping automatically
        nodePadding: 1.1,       // Multiplier for node radius in repulsion calculation ( > 1 means padding)
    };

    constructor(space, config = {}) {
        if (!space) throw new Error("ForceLayout requires a SpaceGraph instance.");
        this.space = space;
        this.settings = { ...this.settings, ...config }; // Allow overriding defaults
    }

    // --- Public API ---

    addNode(node) {
        if (!this.nodes.some(n => n.id === node.id)) {
            this.nodes.push(node);
            this.velocities.set(node.id, new THREE.Vector3());
            this.kick();
        }
    }

    removeNode(node) {
        this.nodes = this.nodes.filter(n => n !== node);
        this.velocities.delete(node.id);
        this.fixedNodes.delete(node);
        if (this.nodes.length < 2) this.stop();
        else this.kick();
    }

    addEdge(edge) {
        if (!this.edges.includes(edge)) {
            this.edges.push(edge);
            this.kick();
        }
    }

    removeEdge(edge) {
        this.edges = this.edges.filter(e => e !== edge);
        this.kick();
    }

    fixNode(node) {
        if (this.nodes.includes(node)) {
            this.fixedNodes.add(node);
            this.velocities.get(node.id)?.set(0, 0, 0);
        }
    }

    releaseNode(node) {
        this.fixedNodes.delete(node);
        // Kick happens on drag end or resize end in UIManager
    }

    // Run simulation for a fixed number of steps (useful for initial layout)
    runOnce(steps = 100) {
        console.log(`ForceLayout: Running ${steps} initial steps...`);
        let i = 0;
        for (; i < steps; i++) {
            if (this._calculateStep() < this.settings.minEnergyThreshold) break;
        }
        console.log(`ForceLayout: Initial steps completed after ${i} iterations.`);
        this.space.updateNodesAndEdges(); // Update visuals after running steps
    }

    start() {
        if (this.isRunning || this.nodes.length < 2) return;

        console.log("ForceLayout: Starting simulation.");
        this.isRunning = true;
        this.lastKickTime = Date.now();

        const loop = () => {
            if (!this.isRunning) return;

            this.totalEnergy = this._calculateStep();
            this.space.updateNodesAndEdges();

            // Check conditions for auto-stopping
            const timeSinceKick = Date.now() - this.lastKickTime;
            if (this.totalEnergy < this.settings.minEnergyThreshold && timeSinceKick > this.settings.autoStopDelay) {
                this.stop();
            } else {
                this.animationFrameId = requestAnimationFrame(loop);
            }
        };
        this.animationFrameId = requestAnimationFrame(loop);
    }

    stop() {
        if (!this.isRunning) return;
        this.isRunning = false;
        if (this.animationFrameId) cancelAnimationFrame(this.animationFrameId);
        this.animationFrameId = null;
        clearTimeout(this.autoStopTimeout);
        this.autoStopTimeout = null;
        console.log("ForceLayout: Simulation stopped. Energy:", this.totalEnergy.toFixed(4));
    }

    kick(intensity = 1) {
        if (this.nodes.length < 1) return;
        this.lastKickTime = Date.now();
        this.totalEnergy = Infinity; // Reset energy state

        // Add a small random velocity impulse to non-fixed nodes
        this.nodes.forEach(node => {
            if (!this.fixedNodes.has(node)) {
                const randomImpulse = new THREE.Vector3(
                    Math.random() - 0.5,
                    Math.random() - 0.5,
                    (Math.random() - 0.5) * this.settings.zSpreadFactor // Less Z impulse
                ).normalize().multiplyScalar(intensity * (1 + Math.random()));
                this.velocities.get(node.id)?.add(randomImpulse);
            }
        });

        if (!this.isRunning) this.start();

        // Reset the auto-stop timer on each kick
        clearTimeout(this.autoStopTimeout);
        this.autoStopTimeout = setTimeout(() => {
            // Check energy again before stopping, might have been kicked again recently
            if (this.isRunning && this.totalEnergy < this.settings.minEnergyThreshold) {
                this.stop();
            }
        }, this.settings.autoStopDelay);
    }

    setSettings(newSettings) {
        this.settings = { ...this.settings, ...newSettings };
        console.log("ForceLayout settings updated:", this.settings);
        this.kick();
    }

    // --- Internal Calculation ---

    _calculateStep() {
        if (this.nodes.length < 2) return 0;

        let currentTotalEnergy = 0;
        // Use a Map for forces for easier access by nodeId
        const forces = new Map(this.nodes.map(node => [node.id, new THREE.Vector3()]));

        const {
            repulsion, attraction, idealEdgeLength, centerStrength,
            gravityCenter, zSpreadFactor, damping, nodePadding
        } = this.settings;

        // 1. Repulsion Force (Node-Node) - O(N^2)
        for (let i = 0; i < this.nodes.length; i++) {
            const nodeA = this.nodes[i];
            for (let j = i + 1; j < this.nodes.length; j++) {
                const nodeB = this.nodes[j];

                const delta = new THREE.Vector3().subVectors(nodeB.position, nodeA.position);
                let distanceSq = delta.lengthSq();

                // Avoid division by zero or extreme forces at very close distances
                if (distanceSq < 1e-3) {
                    distanceSq = 1e-3;
                    delta.set(Math.random() - 0.5, Math.random() - 0.5, (Math.random() - 0.5) * zSpreadFactor).normalize().multiplyScalar(0.1);
                }
                const distance = Math.sqrt(distanceSq);

                // Calculate effective radii (approximate based on area) for padding
                const radiusA = Math.sqrt(nodeA.size.width * nodeA.size.height) / 2;
                const radiusB = Math.sqrt(nodeB.size.width * nodeB.size.height) / 2;
                const combinedRadius = (radiusA + radiusB) * nodePadding;

                // Calculate repulsion force magnitude (inverse square law)
                let forceMag = -repulsion / distanceSq;

                // Add extra repulsion if nodes overlap (distance < combinedRadius)
                const overlap = combinedRadius - distance;
                if (overlap > 0) {
                    forceMag -= (repulsion * Math.pow(overlap, 2) * 0.005) / distance; // Tunable factor
                }

                const forceVec = delta.normalize().multiplyScalar(forceMag);
                forceVec.z *= zSpreadFactor;

                if (!this.fixedNodes.has(nodeA)) forces.get(nodeA.id)?.add(forceVec);
                if (!this.fixedNodes.has(nodeB)) forces.get(nodeB.id)?.sub(forceVec);
            }
        }

        // 2. Attraction Force (Edge Springs) - O(E)
        this.edges.forEach(edge => {
            const { source, target } = edge;
            // Ensure both source and target nodes still exist in the layout
            if (!source || !target || !this.velocities.has(source.id) || !this.velocities.has(target.id)) return;

            const delta = new THREE.Vector3().subVectors(target.position, source.position);
            const distance = delta.length() + 1e-6; // Add epsilon to avoid division by zero

            // Adjust ideal length based on edge constraint data
            const constraintFactor = edge.data.constraint === 'tight' ? 0.7 : (edge.data.constraint === 'loose' ? 1.3 : 1.0);
            const effectiveIdealLength = idealEdgeLength * constraintFactor;

            // Hooke's Law: F = -k * x
            const displacement = distance - effectiveIdealLength;
            const forceMag = attraction * displacement;

            const forceVec = delta.normalize().multiplyScalar(forceMag);
            forceVec.z *= zSpreadFactor;

            if (!this.fixedNodes.has(source)) forces.get(source.id)?.add(forceVec);
            if (!this.fixedNodes.has(target)) forces.get(target.id)?.sub(forceVec);
        });

        // 3. Center Gravity Force - O(N)
        if (centerStrength > 0) {
            this.nodes.forEach(node => {
                if (this.fixedNodes.has(node)) return;

                const forceVec = new THREE.Vector3().subVectors(gravityCenter, node.position).multiplyScalar(centerStrength);
                forceVec.z *= zSpreadFactor * 0.5; // Make Z gravity even weaker
                forces.get(node.id)?.add(forceVec);
            });
        }

        // 4. Apply Forces and Update Velocities/Positions - O(N)
        this.nodes.forEach(node => {
            if (this.fixedNodes.has(node)) return;

            const force = forces.get(node.id);
            const velocity = this.velocities.get(node.id);
            if (!force || !velocity) return; // Should not happen if node is in layout

            // Update velocity: v = (v + F) * damping
            velocity.add(force).multiplyScalar(damping);

            // Limit velocity to prevent instability
            const speed = velocity.length();
            const maxSpeed = 50; // Tunable max speed
            if (speed > maxSpeed) {
                velocity.multiplyScalar(maxSpeed / speed);
            }

            node.position.add(velocity);

            // Accumulate kinetic energy (0.5 * m * v^2), assume mass = 1
            currentTotalEnergy += velocity.lengthSq();
        });

        return currentTotalEnergy;
    }

    dispose() {
        this.stop(); // Ensure simulation is stopped and timers cleared
        this.nodes = [];
        this.edges = [];
        this.velocities.clear();
        this.fixedNodes.clear();
        this.space = null;
        console.log("ForceLayout disposed.");
    }
}


// --- Initialization ---

function init() {
    const container = $('#space');
    const contextMenuEl = $('#context-menu');
    const confirmDialogEl = $('#confirm-dialog');

    if (!container) {
        console.error("Initialization Failed: Mind map container '#space' not found!");
        return;
    }
    if (!contextMenuEl) {
        console.error("Initialization Failed: Context menu element '#context-menu' not found!");
        return;
    }
    if (!confirmDialogEl) {
        console.error("Initialization Failed: Confirm dialog element '#confirm-dialog' not found!");
        return;
    }

    try {
        const space = new SpaceGraph(container);
        const layout = new ForceLayout(space);
        space.layout = layout;
        const uiManager = new UIManager(space, contextMenuEl, confirmDialogEl);
        space.ui = uiManager;

        createExampleGraph(space);

        layout.runOnce(200); // Settle initial positions
        space.centerView(null, 0.8); // Center smoothly after initial layout
        layout.start(); // Start continuous layout adjustments
        space.animate(); // Start render loop

        window.space = space; // Expose for debugging
        console.log("Mind Map Initialized Successfully.");

    } catch (error) {
        console.error("Initialization Failed:", error);
        // Optionally display an error message to the user
        container.innerHTML = `<div style="color: red; padding: 20px;">Error initializing Mind Map: ${error.message}</div>`;
    }
}

function createExampleGraph(space) {
    console.log("Creating example graph...");
    const colors = ['#2a2a50', '#2a402a', '#402a2a', '#40402a', '#2a4040', '#402a40', '#503030'];
    let colorIndex = 0;
    const nextColor = () => colors[colorIndex++ % colors.length];

    // Core Node
    const n1 = space.addNode(new NoteNode('core-node', { x: 0, y: 0, z: 0 }, {
        content: "<h1>🚀 READY 🧠</h1><p>Mind Map Demo</p>",
        width: 300, height: 110, backgroundColor: nextColor()
    }));

    // Features Branch
    const n_features = space.addNode(new NoteNode('features-node', { x: 350, y: 100, z: 20 }, {
        content: "<h2>Features ✨</h2><ul><li>Node Drag/Resize</li><li>Rich Text Notes</li><li>Context Menus</li><li>Edge Selection/Menu</li><li>Force Layout</li><li>Keyboard Shortcuts</li></ul>",
        width: 240, height: 190, backgroundColor: nextColor()
    }));
    space.addEdge(n1, n_features);

    const n_autozoom = space.addNode(new NoteNode('autozoom-node', { x: 600, y: 150, z: 30 }, {
        content: "<h3>Autozoom Detail</h3><p><b>Middle-click</b> a node to zoom. Click again or use menu/spacebar to return. (Uses view history)</p>",
        width: 210, height: 130, backgroundColor: colors[1] // Reuse color
    }));
    space.addEdge(n_features, n_autozoom, { constraint: 'tight' }); // Tighter link

    // Tech Branch
    const n_tech = space.addNode(new NoteNode('tech-node', { x: -350, y: 100, z: -10 }, {
        content: "<h2>Technology 💻</h2><p><code>HTML</code> <code>CSS</code> <code>JS (ESM)</code></p><p><b>Three.js</b> + <b>CSS3DRenderer</b></p><p><b>GSAP</b> for animation</p>",
        width: 250, height: 140, backgroundColor: nextColor()
    }));
    space.addEdge(n1, n_tech);

    // Style Branch
    const n_style = space.addNode(new NoteNode('style-node', { x: 0, y: -250, z: 0 }, {
        content: "<h2>Style 🎨</h2><p>✨ Minimal Dark Mode</p><p>🎨 Varied Node Colors</p><p>🕸️ Subtle Dot Grid BG</p><p>🔧 Simple CSS Vars</p>",
        width: 220, height: 140, backgroundColor: nextColor()
    }));
    space.addEdge(n1, n_style);

    // Interactive Node Example
    const interactiveNodeId = Utils.generateId('interactive');
    const n_interactive = space.addNode(new NoteNode(interactiveNodeId, { x: 350, y: -150, z: -30 }, {
        content: `<h2>Interactive HTML</h2>
<p>Slider value: <span id="slider-val-${interactiveNodeId}">50</span></p>
<input type="range" min="0" max="100" value="50" style="width: 90%; pointer-events: auto; cursor: pointer;"
       oninput="document.getElementById('slider-val-${interactiveNodeId}').textContent = this.value; event.stopPropagation();"
       onmousedown="event.stopPropagation();" ontouchstart="event.stopPropagation();">
    <button onclick="alert('Button inside node ${interactiveNodeId} clicked!'); event.stopPropagation();"
            style="pointer-events: auto; cursor: pointer; margin-top: 5px;">Click Me</button>`,
        width: 230, height: 170, backgroundColor: nextColor()
    }));
    space.addEdge(n_features, n_interactive);
    space.addEdge(n_style, n_interactive, { constraint: 'loose' }); // Cross link, looser

    // Hierarchical / Fractal Example
    const n_fractal_root = space.addNode(new NoteNode('fractal-root', { x: -400, y: -200, z: 50 }, {
        content: "<h3>Hierarchy</h3>", width: 120, height: 50, backgroundColor: colors[0]
    }));
    space.addEdge(n_tech, n_fractal_root);

    // Create child nodes programmatically
    for (let i = 0; i < 3; i++) {
        const angle = (i / 3) * Math.PI * 2 + 0.2; // Add slight offset
        const parentPos = n_fractal_root.position;
        const childPos = {
            x: parentPos.x + Math.cos(angle) * 180,
            y: parentPos.y + Math.sin(angle) * 180,
            z: parentPos.z + (Math.random() - 0.5) * 50
        };
        const n_level1 = space.addNode(new NoteNode(null, childPos, {
            content: `L1-${i + 1}`, width: 80, height: 40, backgroundColor: colors[1]
        }));
        space.addEdge(n_fractal_root, n_level1);

        // Add grandchildren
        for (let j = 0; j < 2; j++) {
            const angle2 = (j / 2) * Math.PI * 2 + Math.random() * 0.5;
            const parentPosL2 = n_level1.position;
            const grandChildPos = {
                x: parentPosL2.x + Math.cos(angle2) * 100,
                y: parentPosL2.y + Math.sin(angle2) * 100,
                z: parentPosL2.z + (Math.random() - 0.5) * 30
            };
            const n_level2 = space.addNode(new NoteNode(null, grandChildPos, {
                content: `L2-${j + 1}`, width: 60, height: 30, backgroundColor: colors[2]
            }));
            space.addEdge(n_level1, n_level2, { constraint: 'tight' });
        }
    }
    console.log("Example graph created:", space.nodes.size, "nodes,", space.edges.size, "edges.");
}


// --- Start the application ---
// Ensure DOM is ready before initializing
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
} else {
    init();
}