import { CapMatrix } from '@tremaze/capacitor-matrix';

window.testEcho = () => {
    const inputValue = document.getElementById("echoInput").value;
    CapMatrix.echo({ value: inputValue })
}
