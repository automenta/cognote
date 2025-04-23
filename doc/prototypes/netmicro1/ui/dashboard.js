import * as THREE from 'three';
import { CSS3DRenderer, CSS3DObject } from 'three/addons/renderers/CSS3DRenderer.js';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import TWEEN from 'https://cdnjs.cloudflare.com/ajax/libs/tween.js/25.0.0/tween.esm.js';

class Dashboard {
    constructor() {
        this.scene = new THREE.Scene();
        this.camera = new THREE.PerspectiveCamera(75, window.innerWidth / window.innerHeight, 0.1, 1000);
        this.renderer = new THREE.WebGLRenderer({ canvas: document.getElementById('canvas'), antialias: true });
        this.cssRenderer = new CSS3DRenderer();
        this.controls = new OrbitControls(this.camera, this.cssRenderer.domElement);
        this.nodes = new Map();
        this.edges = new Map();
        this.ws = new WebSocket('ws://localhost:8000');
        this.hud = {
            status: document.getElementById('status'),
            prompt: document.getElementById('prompt-input'),
            toggleLog: document.getElementById('toggle-log'),
            log: document.getElementById('log'),
            timeline: null // Added dynamically
        };
        this.stateHistory = [];
        this.timeIndex = -1;

        this.init();
    }

    init() {
        // Renderer setup
        this.renderer.setSize(window.innerWidth, window.innerHeight);
        this.cssRenderer.setSize(window.innerWidth, window.innerHeight);
        this.cssRenderer.domElement.style.position = 'absolute';
        this.cssRenderer.domElement.style.top = '0';
        document.getElementById('container').appendChild(this.cssRenderer.domElement);

        // Camera setup
        this.camera.position.set(0, 50, 100);
        this.controls.enableDamping = true;
        this.controls.dampingFactor = 0.1;
        this.controls.minDistance = 10;
        this.controls.maxDistance = 500;

        // Scene setup
        this.nodeGroup = new THREE.Group();
        this.overlayGroup = new THREE.Group();
        this.edgeGroup = new THREE.Group();
        this.scene.add(this.nodeGroup, this.overlayGroup, this.edgeGroup);

        // Event handlers
        this.ws.onmessage = (e) => this.handleMessage(e);
        this.hud.prompt.onkeypress = (e) => this.handlePrompt(e);
        this.hud.toggleLog.onclick = () => this.toggleLog();
        window.onresize = () => this.onResize();

        // Timeline HUD
        this.addTimeline();

        this.animate();
    }

    animate() {
        requestAnimationFrame(() => this.animate());
        this.simulate();
        this.controls.update();
        this.renderer.render(this.scene, this.camera);
        this.cssRenderer.render(this.scene, this.camera);
        TWEEN.update();
    }

    // --- Core Methods ---
    handleMessage(event) {
        const { type, data } = JSON.parse(event.data);
        if (type === "graph") {
            this.stateHistory.push({ nodes: data.nodes, edges: data.edges, time: Date.now() });
            this.timeIndex = this.stateHistory.length - 1;
            this.updateGraph(data.nodes, data.edges);
            this.updateTimeline();
        } else if (type === "log") {
            this.addLogEntry(data.message);
        }
    }

    handlePrompt(e) {
        if (e.key === 'Enter' && this.hud.prompt.value.trim()) {
            const cmd = this.hud.prompt.value.trim();
            if (cmd.startsWith("search ")) {
                this.searchNodes(cmd.slice(7));
            } else {
                this.ws.send(JSON.stringify({ type: 'prompt', message: cmd }));
            }
            this.hud.prompt.value = '';
        }
    }

    toggleLog() {
        this.hud.log.style.display = this.hud.log.style.display === 'none' ? 'block' : 'none';
    }

    updateGraph(nodes, edges) {
        const nodeMap = new Map(nodes.map(n => [n.id, n]));
        const edgeKeySet = new Set(edges.map(e => `${e.source}-${e.target}`));

        // Clean up removed nodes and edges
        for (const [id] of this.nodes) {
            if (!nodeMap.has(id)) this.removeNode(id);
        }
        for (const [key] of this.edges) {
            if (!edgeKeySet.has(key)) this.removeEdge(key);
        }

        // Add or update nodes
        nodes.forEach(n => {
            if (!this.nodes.has(n.id)) this.addNode(n);
            else this.updateNode(n);
        });

        // Add or update edges
        edges.forEach(e => {
            const key = `${e.source}-${e.target}`;
            if (!this.edges.has(key)) this.addEdge(e);
            else this.updateEdge(e);
        });

        this.updateStatus();
    }

    updateEdge(data) {
        const key = `${data.source}-${data.target}`;
        const edge = this.edges.get(key);
        if (edge) {
            edge.line.geometry.setFromPoints([
                this.nodes.get(data.source)?.pos || new THREE.Vector3(),
                this.nodes.get(data.target)?.pos || new THREE.Vector3()
            ]);
            edge.line.material.linewidth = Math.max(1, Math.min(5, (data.weight || 1)));
        }
    }

    updateEdges() {
        for (const [, edge] of this.edges) {
            edge.line.geometry.setFromPoints([
                this.nodes.get(edge.source)?.pos || new THREE.Vector3(),
                this.nodes.get(edge.target)?.pos || new THREE.Vector3()
            ]);
        }
    }

    simulate() {
        const repulsion = 100, attraction = 0.05, damping = 0.9;
        const nodeArray = Array.from(this.nodes.entries());

        // Force-directed simulation
        for (let i = 0; i < nodeArray.length; i++) {
            const [id1, n1] = nodeArray[i];
            for (let j = i + 1; j < nodeArray.length; j++) {
                const [id2, n2] = nodeArray[j];
                const dir = n1.pos.clone().sub(n2.pos);
                const dist = Math.max(dir.length(), 0.1);
                const force = repulsion / (dist * dist);
                dir.normalize().multiplyScalar(force);
                n1.vel.add(dir);
                n2.vel.sub(dir);
            }
        }

        for (const [, edge] of this.edges) {
            const n1 = this.nodes.get(edge.source), n2 = this.nodes.get(edge.target);
            if (n1 && n2) {
                const dir = n2.pos.clone().sub(n1.pos);
                const dist = dir.length();
                const force = attraction * dist;
                dir.normalize().multiplyScalar(force);
                n1.vel.add(dir);
                n2.vel.sub(dir);
            }
        }

        for (const [, node] of this.nodes) {
            node.vel.multiplyScalar(damping);
            node.pos.add(node.vel);
            node.sphere.position.copy(node.pos);
            node.overlay.position.copy(node.pos);
        }

        this.updateEdges();
    }

    // --- UI Component API ---
    addNode(data) {
        const size = Math.max(5, Math.min(20, data.priority / 10 || 5));
        const color = data.status === "running" ? 0x00ff00 : data.status === "pending" ? 0xffff00 : 0xff0000;
        const sphere = new THREE.Mesh(new THREE.SphereGeometry(size), new THREE.MeshBasicMaterial({ color }));
        sphere.position.set(Math.random() * 100 - 50, Math.random() * 100 - 50, Math.random() * 100 - 50);
        this.nodeGroup.add(sphere);

        const div = document.createElement('div');
        div.className = 'node-overlay';
        div.innerHTML = `
            <h3>${data.label || data.id}</h3>
            <p>Status: ${data.status}</p>
            <p>Priority: ${data.priority}</p>
            <button onclick="dashboard.toggleNode('${data.id}')">${data.status === "running" ? "Pause" : "Resume"}</button>
            <button onclick="dashboard.deleteNode('${data.id}')">Delete</button>
            <button onclick="dashboard.toggleDetails('${data.id}')">Details</button>
            <div class="details" style="display:none">${JSON.stringify(data, null, 2)}</div>
        `;
        div.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            this.zoomTo(sphere.position);
        });
        const overlay = new CSS3DObject(div);
        overlay.position.copy(sphere.position);
        this.overlayGroup.add(overlay);

        this.nodes.set(data.id, { sphere, overlay, pos: sphere.position.clone(), vel: new THREE.Vector3(), data });
    }

    updateNode(data) {
        const node = this.nodes.get(data.id);
        if (!node) return;
        node.data = data;
        node.sphere.material.color.set(data.status === "running" ? 0x00ff00 : data.status === "pending" ? 0xffff00 : 0xff0000);
        node.overlay.element.innerHTML = `
            <h3>${data.label || data.id}</h3>
            <p>Status: ${data.status}</p>
            <p>Priority: ${data.priority}</p>
            <button onclick="dashboard.toggleNode('${data.id}')">${data.status === "running" ? "Pause" : "Resume"}</button>
            <button onclick="dashboard.deleteNode('${data.id}')">Delete</button>
            <button onclick="dashboard.toggleDetails('${data.id}')">Details</button>
            <div class="details" style="display:${node.overlay.element.querySelector('.details')?.style.display || 'none'}">${JSON.stringify(data, null, 2)}</div>
        `;
    }

    removeNode(id) {
        const node = this.nodes.get(id);
        if (!node) return;
        this.nodeGroup.remove(node.sphere);
        this.overlayGroup.remove(node.overlay);
        this.nodes.delete(id);
        this.edges = new Map([...this.edges].filter(([k]) => !k.startsWith(id) && !k.endsWith(id)));
        this.updateStatus();
    }

    addEdge(data) {
        const key = `${data.source}-${data.target}`;
        const thickness = Math.max(1, Math.min(5, (data.weight || 1)));
        const geometry = new THREE.BufferGeometry().setFromPoints([
            this.nodes.get(data.source)?.pos || new THREE.Vector3(),
            this.nodes.get(data.target)?.pos || new THREE.Vector3()
        ]);
        const material = new THREE.LineBasicMaterial({ color: 0xffffff, linewidth: thickness });
        const line = new THREE.Line(geometry, material);
        this.edgeGroup.add(line);
        this.edges.set(key, { source: data.source, target: data.target, line });
    }

    updateEdge(data) {
        const key = `${data.source}-${data.target}`;
        const edge = this.edges.get(key);
        if (edge) {
            edge.line.geometry.setFromPoints([
                this.nodes.get(data.source)?.pos || new THREE.Vector3(),
                this.nodes.get(data.target)?.pos || new THREE.Vector3()
            ]);
            edge.line.material.linewidth = Math.max(1, Math.min(5, (data.weight || 1)));
        }
    }

    removeEdge(key) {
        const edge = this.edges.get(key);
        if (edge) {
            this.edgeGroup.remove(edge.line);
            this.edges.delete(key);
        }
    }

    // --- Interaction Methods ---
    toggleNode(id) {
        const node = this.nodes.get(id);
        if (!node) return;
        const newStatus = node.data.status === "running" ? "pending" : "running";
        this.ws.send(JSON.stringify({ type: 'prompt', message: newStatus === "running" ? `resume ${id}` : `pause ${id}` }));
    }

    deleteNode(id) {
        this.ws.send(JSON.stringify({ type: 'prompt', message: `delete ${id}` }));
    }

    toggleDetails(id) {
        const node = this.nodes.get(id);
        if (!node) return;
        const details = node.overlay.element.querySelector('.details');
        details.style.display = details.style.display === 'none' ? 'block' : 'none';
    }

    searchNodes(query) {
        const q = query.toLowerCase();
        for (const [id, node] of this.nodes) {
            const match = id.toLowerCase().includes(q) || node.data.label?.toLowerCase().includes(q) || node.data.status.toLowerCase().includes(q);
            node.sphere.material.color.set(match ? 0xffa500 : node.data.status === "running" ? 0x00ff00 : node.data.status === "pending" ? 0xffff00 : 0xff0000);
        }
    }

    // --- Camera Presets ---
    setCameraPreset(view) {
        const pos = view === "top" ? { x: 0, y: 100, z: 0 } : view === "orbit" ? { x: 0, y: 0, z: 100 } : { x: 0, y: 0, z: 100 };
        new TWEEN.Tween(this.camera.position)
            .to(pos, 1000)
            .easing(TWEEN.Easing.Quadratic.Out)
            .onUpdate(() => this.controls.update())
            .start();
        new TWEEN.Tween(this.controls.target)
            .to({ x: 0, y: 0, z: 0 }, 1000)
            .easing(TWEEN.Easing.Quadratic.Out)
            .start();
    }

    zoomTo(pos) {
        const target = pos.clone();
        const distance = 50;
        const dir = this.camera.position.clone().sub(target).normalize();
        const newPos = target.clone().add(dir.multiplyScalar(distance));
        new TWEEN.Tween(this.camera.position)
            .to(newPos, 1000)
            .easing(TWEEN.Easing.Quadratic.Out)
            .onUpdate(() => this.controls.update())
            .start();
        new TWEEN.Tween(this.controls.target)
            .to(target, 1000)
            .easing(TWEEN.Easing.Quadratic.Out)
            .start();
    }

    // --- HUD Management ---
    updateStatus() {
        this.hud.status.textContent = `Nodes: ${this.nodes.size} | Edges: ${this.edges.size}`;
    }

    addLogEntry(msg) {
        const entry = document.createElement('div');
        entry.textContent = msg;
        this.hud.log.appendChild(entry);
        this.hud.log.scrollTop = this.hud.log.scrollHeight;
    }

    addTimeline() {
        const timeline = document.createElement('input');
        timeline.type = 'range';
        timeline.min = '0';
        timeline.max = '0';
        timeline.value = '0';
        timeline.style.width = '200px';
        timeline.style.marginTop = '10px';
        timeline.oninput = () => this.setTime(parseInt(timeline.value));
        this.hud.container = document.getElementById('hud');
        this.hud.container.appendChild(timeline);
        this.hud.timeline = timeline;
    }

    updateTimeline() {
        this.hud.timeline.max = String(this.stateHistory.length - 1);
        this.hud.timeline.value = String(this.timeIndex);
    }

    setTime(index) {
        if (index < 0 || index >= this.stateHistory.length) return;
        this.timeIndex = index;
        const state = this.stateHistory[index];
        this.updateGraph(state.nodes, state.edges);
    }

    // --- Utility ---
    onResize() {
        this.camera.aspect = window.innerWidth / window.innerHeight;
        this.camera.updateProjectionMatrix();
        this.renderer.setSize(window.innerWidth, window.innerHeight);
        this.cssRenderer.setSize(window.innerWidth, window.innerHeight);
    }
}

const dashboard = new Dashboard();