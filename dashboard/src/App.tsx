import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import Dashboard from './pages/Dashboard/Dashboard';
import Search from './pages/Search';
import Entity from './pages/Entity';
import Source from './pages/Source';
import About from './pages/About';
import Monitoring from './pages/Monitoring';
import ComponentSheet from './pages/ComponentSheet';

/** The site opens on About; links from before the overview moved to /overview still land there. */
function Home() {
  const { search } = useLocation();
  return new URLSearchParams(search).has('section') ? <Navigate to={'/overview' + search} replace /> : <About />;
}

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/overview" element={<Dashboard />} />
      <Route path="/search" element={<Search />} />
      <Route path="/entity/:source/:id" element={<Entity />} />
      <Route path="/source/:id" element={<Source />} />
      <Route path="/monitoring" element={<Monitoring />} />
      <Route path="/components" element={<ComponentSheet />} />
    </Routes>
  );
}
