import { Route, Routes } from 'react-router-dom';
import Dashboard from './pages/Dashboard/Dashboard';
import Search from './pages/Search';
import Entity from './pages/Entity';
import Source from './pages/Source';
import Demo from './pages/Demo';
import ComponentSheet from './pages/ComponentSheet';

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Dashboard />} />
      <Route path="/search" element={<Search />} />
      <Route path="/entity/:source/:id" element={<Entity />} />
      <Route path="/source/:id" element={<Source />} />
      <Route path="/demo" element={<Demo />} />
      <Route path="/components" element={<ComponentSheet />} />
    </Routes>
  );
}
