import com.cedarsoftware.ncube.ApplicationID
import com.cedarsoftware.ncube.Axis
import com.cedarsoftware.ncube.Column
import com.cedarsoftware.ncube.NCube
import com.cedarsoftware.ncube.NCubeAppContext
import com.cedarsoftware.ncube.NCubeRuntimeClient
import com.cedarsoftware.ncube.exception.RuleJump
import com.cedarsoftware.ncube.exception.RuleStop
import com.cedarsoftware.util.IOUtilities
import com.cedarsoftware.util.StringUtilities

NCubeRuntimeClient getRuntimeClient()
{
    return NCubeAppContext.runtimeClient
}

NCube getCube(String name = ncube.name, boolean quiet = false)
{
    if (StringUtilities.equalsIgnoreCase(ncube.name, name))
    {
        return ncube
    }
    NCube cube = runtimeClient.getCube(ncube.applicationID, name)
    if (cube == null && !quiet)
    {
        throw new IllegalArgumentException('n-cube: ' + name + ' not found.')
    }
    return cube
}

Axis getAxis(String axisName, String cubeName = ncube.name)
{
    Axis axis = getCube(cubeName).getAxis(axisName)
    if (axis == null)
    {
        throw new IllegalArgumentException('Axis: ' + axisName + ', does not exist on n-cube: ' + cubeName + ', app: ' + ncube.applicationID)
    }
    return axis
}

Column getColumn(Comparable value, String axisName, String cubeName = ncube.name)
{
    return getAxis(axisName, cubeName).findColumn(value)
}

def at(Map coord, String cubeName = ncube.name, def defaultValue = null)
{
    input.putAll(coord)
    return getCube(cubeName).getCell(input, output, defaultValue)
}

def at(Map coord, NCube cube, def defaultValue = null)
{
    input.putAll(coord)
    return cube.getCell(input, output, defaultValue)
}

def at(Map coord, String cubeName, def defaultValue, ApplicationID appId)
{
    NCube target = runtimeClient.getCube(appId, cubeName)
    input.putAll(coord)
    return target.getCell(input, output, defaultValue)
}

def go(Map coord, String cubeName = ncube.name, def defaultValue = null)
{
    return getCube(cubeName).getCell(coord, output, defaultValue)
}

def go(Map coord, NCube cube, def defaultValue = null)
{
    return cube.getCell(coord, output, defaultValue)
}

def go(Map coord, String cubeName, def defaultValue, ApplicationID appId)
{
    NCube target = runtimeClient.getCube(appId, cubeName)
    return target.getCell(coord, output, defaultValue)
}

String url(String url)
{
    byte[] bytes = urlToBytes(url)
    if (bytes == null)
    {
        return null
    }
    return StringUtilities.createUtf8String(bytes)
}

byte[] urlToBytes(String url)
{
    InputStream inStream = getClass().getResourceAsStream(url)
    byte[] bytes = IOUtilities.inputStreamToBytes(inStream)
    IOUtilities.close(inStream as Closeable)
    return bytes
}

def ruleStop()
{
    throw new RuleStop()
}

def jump(Map coord)
{
    input.putAll(coord);
    throw new RuleJump(input)
}

static long now()
{
    return System.nanoTime()
}

static double elapsedMillis(long begin, long end)
{
    return (double) (end - begin) / 1000000.0d
}
